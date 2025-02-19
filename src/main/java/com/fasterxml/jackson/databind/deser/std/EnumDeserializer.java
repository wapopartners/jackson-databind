package com.fasterxml.jackson.databind.deser.std;

import java.io.IOException;

import com.fasterxml.jackson.annotation.JsonFormat;

import com.fasterxml.jackson.core.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JacksonStdImpl;
import com.fasterxml.jackson.databind.deser.SettableBeanProperty;
import com.fasterxml.jackson.databind.deser.ValueInstantiator;
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.databind.util.ClassUtil;
import com.fasterxml.jackson.databind.util.CompactStringObjectMap;
import com.fasterxml.jackson.databind.util.EnumResolver;

/**
 * Deserializer class that can deserialize instances of
 * specified Enum class from Strings and Integers.
 */
@JacksonStdImpl
public class EnumDeserializer
    extends StdScalarDeserializer<Object>
{
    protected Object[] _enumsByIndex;

    private final Enum<?> _enumDefaultValue;

    protected final CompactStringObjectMap _lookupByName;

    /**
     * Alternatively, we may need a different lookup object if "use toString"
     * is defined.
     */
    protected CompactStringObjectMap _lookupByToString;

    protected final Boolean _caseInsensitive;

    public EnumDeserializer(EnumResolver byNameResolver, Boolean caseInsensitive)
    {
        super(byNameResolver.getEnumClass());
        _lookupByName = byNameResolver.constructLookup();
        _enumsByIndex = byNameResolver.getRawEnums();
        _enumDefaultValue = byNameResolver.getDefaultValue();
        _caseInsensitive = caseInsensitive;
    }

    protected EnumDeserializer(EnumDeserializer base, Boolean caseInsensitive)
    {
        super(base);
        _lookupByName = base._lookupByName;
        _enumsByIndex = base._enumsByIndex;
        _enumDefaultValue = base._enumDefaultValue;
        _caseInsensitive = caseInsensitive;
    }

    /**
     * Factory method used when Enum instances are to be deserialized
     * using a creator (static factory method)
     * 
     * @return Deserializer based on given factory method
     */
    public static JsonDeserializer<?> deserializerForCreator(DeserializationConfig config,
            Class<?> enumClass, AnnotatedMethod factory,
            ValueInstantiator valueInstantiator, SettableBeanProperty[] creatorProps)
    {
        if (config.canOverrideAccessModifiers()) {
            ClassUtil.checkAndFixAccess(factory.getMember(),
                    config.isEnabled(MapperFeature.OVERRIDE_PUBLIC_ACCESS_MODIFIERS));
        }
        return new FactoryBasedEnumDeserializer(enumClass, factory,
                factory.getParameterType(0),
                valueInstantiator, creatorProps);
    }

    /**
     * Factory method used when Enum instances are to be deserialized
     * using a zero-/no-args factory method
     * 
     * @return Deserializer based on given no-args factory method
     */
    public static JsonDeserializer<?> deserializerForNoArgsCreator(DeserializationConfig config,
            Class<?> enumClass, AnnotatedMethod factory)
    {
        if (config.canOverrideAccessModifiers()) {
            ClassUtil.checkAndFixAccess(factory.getMember(),
                    config.isEnabled(MapperFeature.OVERRIDE_PUBLIC_ACCESS_MODIFIERS));
        }
        return new FactoryBasedEnumDeserializer(enumClass, factory);
    }

    public EnumDeserializer withResolved(Boolean caseInsensitive) {
        if (_caseInsensitive == caseInsensitive) {
            return this;
        }
        return new EnumDeserializer(this, caseInsensitive);
    }
    
    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext ctxt,
            BeanProperty property) throws JsonMappingException
    {
        Boolean caseInsensitive = findFormatFeature(ctxt, property, handledType(),
                JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES);
        if (caseInsensitive == null) {
            caseInsensitive = _caseInsensitive;
        }
        return withResolved(caseInsensitive);
    }

    /*
    /**********************************************************
    /* Default JsonDeserializer implementation
    /**********************************************************
     */

    /**
     * Because of costs associated with constructing Enum resolvers,
     * let's cache instances by default.
     */
    @Override
    public boolean isCachable() { return true; }

    @Override // since 2.12
    public LogicalType logicalType() {
        return LogicalType.Enum;
    }

    @Override
    public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException
    {
        String text;
        JsonToken curr = p.currentToken();

        // Usually should just get string value:
        // 04-Sep-2020, tatu: for 2.11.3 / 2.12.0, removed "FIELD_NAME" as allowed;
        //   did not work and gave odd error message.
        if (curr == JsonToken.VALUE_STRING) {
            text = p.getText();
        // But let's consider int acceptable as well (if within ordinal range)
        } else if (curr == JsonToken.VALUE_NUMBER_INT) {
            // ... unless told not to do that
            int index = p.getIntValue();
            if (ctxt.isEnabled(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)) {
                return ctxt.handleWeirdNumberValue(_enumClass(), index,
                        "not allowed to deserialize Enum value out of number: disable DeserializationConfig.DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS to allow"
                        );
            }
            if (index >= 0 && index < _enumsByIndex.length) {
                return _enumsByIndex[index];
            }
            if ((_enumDefaultValue != null)
                    && ctxt.isEnabled(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)) {
                return _enumDefaultValue;
            }
            if (!ctxt.isEnabled(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)) {
                return ctxt.handleWeirdNumberValue(_enumClass(), index,
                        "index value outside legal index range [0..%s]",
                        _enumsByIndex.length-1);
            }
            return null;
        } else if (curr == JsonToken.START_OBJECT) {
            // 29-Jun-2020, tatu: New! "Scalar from Object" (mostly for XML)
            text = ctxt.extractScalarFromObject(p, this, _valueClass);
        } else {
            return _deserializeOther(p, ctxt);
        }

        CompactStringObjectMap lookup = ctxt.isEnabled(DeserializationFeature.READ_ENUMS_USING_TO_STRING)
                ? _getToStringLookup(ctxt) : _lookupByName;
        Object result = lookup.find(text);
        if (result == null) {
            String trimmed = text.trim();
            if ((trimmed == text) || (result = lookup.find(trimmed)) == null) {
                return _deserializeAltString(p, ctxt, lookup, trimmed);
            }
        }
        return result;
    }

    /*
    /**********************************************************
    /* Internal helper methods
    /**********************************************************
     */
    
    private final Object _deserializeAltString(JsonParser p, DeserializationContext ctxt,
            CompactStringObjectMap lookup, String name) throws IOException
    {
        name = name.trim();
        if (name.length() == 0) {
            if (ctxt.isEnabled(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)) {
                return getEmptyValue(ctxt);
            }
        } else {
            // [databind#1313]: Case insensitive enum deserialization
            if (Boolean.TRUE.equals(_caseInsensitive)) {
                Object match = lookup.findCaseInsensitive(name);
                if (match != null) {
                    return match;
                }
            } else if (!ctxt.isEnabled(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)) {
                // [databind#149]: Allow use of 'String' indexes as well -- unless prohibited (as per above)
                char c = name.charAt(0);
                if (c >= '0' && c <= '9') {
                    try {
                        int index = Integer.parseInt(name);
                        if (!ctxt.isEnabled(MapperFeature.ALLOW_COERCION_OF_SCALARS)) {
                            return ctxt.handleWeirdStringValue(_enumClass(), name,
"value looks like quoted Enum index, but `DeserializationFeature.ALLOW_COERCION_OF_SCALARS` prevents use"
                                    );
                        }
                        if (index >= 0 && index < _enumsByIndex.length) {
                            return _enumsByIndex[index];
                        }
                    } catch (NumberFormatException e) {
                        // fine, ignore, was not an integer
                    }
                }
            }
        }
        if ((_enumDefaultValue != null)
                && ctxt.isEnabled(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)) {
            return _enumDefaultValue;
        }
        if (!ctxt.isEnabled(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)) {
            return ctxt.handleWeirdStringValue(_enumClass(), name,
                    "not one of the values accepted for Enum class: %s", lookup.keys());
        }
        return null;
    }

    protected Object _deserializeOther(JsonParser p, DeserializationContext ctxt) throws IOException
    {
        // [databind#381]
        if (p.hasToken(JsonToken.START_ARRAY)) {
            return _deserializeFromArray(p, ctxt);
        }
        return ctxt.handleUnexpectedToken(getValueType(ctxt), p);
    }

    protected Class<?> _enumClass() {
        return handledType();
    }

    protected CompactStringObjectMap _getToStringLookup(DeserializationContext ctxt)
    {
        CompactStringObjectMap lookup = _lookupByToString;
        // note: exact locking not needed; all we care for here is to try to
        // reduce contention for the initial resolution
        if (lookup == null) {
            synchronized (this) {
                lookup = EnumResolver.constructUsingToString(ctxt.getConfig(), _enumClass())
                    .constructLookup();
            }
            _lookupByToString = lookup;
        }
        return lookup;
    }
}
