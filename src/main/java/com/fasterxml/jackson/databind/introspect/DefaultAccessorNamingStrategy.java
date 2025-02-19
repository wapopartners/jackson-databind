package com.fasterxml.jackson.databind.introspect;

import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.jdk14.JDK14Util;

/**
 * Default {@link AccessorNamingStrategy} used by Jackson: to be used either as-is,
 * or as base-class with overrides.
 */
public class DefaultAccessorNamingStrategy
    extends AccessorNamingStrategy
{
    protected final MapperConfig<?> _config;
    protected final AnnotatedClass _forClass;

    protected final String _getterPrefix;
    protected final String _isGetterPrefix;

    /**
     * Prefix used by auto-detected mutators ("setters"): usually "set",
     * but differs for builder objects ("with" by default).
     */
    protected final String _mutatorPrefix;

    protected DefaultAccessorNamingStrategy(MapperConfig<?> config, AnnotatedClass forClass,
            String mutatorPrefix, String getterPrefix, String isGetterPrefix)
    {
        _config = config;
        _forClass = forClass;

        _mutatorPrefix = mutatorPrefix;
        _getterPrefix = getterPrefix;
        _isGetterPrefix = isGetterPrefix;
    }
    
    @Override
    public String findNameForIsGetter(AnnotatedMethod am, String name)
    {
        if (_isGetterPrefix != null) {
            final Class<?> rt = am.getRawType();
            if (rt == Boolean.class || rt == Boolean.TYPE) {
                if (name.startsWith(_isGetterPrefix)) { // plus, must return a boolean
                    return stdManglePropertyName(name, 2);
                }
            }
        }
        return null;
    }

    @Override
    public String findNameForRegularGetter(AnnotatedMethod am, String name)
    {
        if ((_getterPrefix != null) && name.startsWith(_getterPrefix)) {
            // 16-Feb-2009, tatu: To handle [JACKSON-53], need to block CGLib-provided
            // method "getCallbacks". Not sure of exact safe criteria to get decent
            // coverage without false matches; but for now let's assume there is
            // no reason to use any such getter from CGLib.

            // 05-Oct-2020, tatu: Removed from Jackson 3.0
            /*
            if ("getCallbacks".equals(name)) {
                if (_isCglibGetCallbacks(am)) {
                    return null;
                }
            } else */
            if ("getMetaClass".equals(name)) {
                // 30-Apr-2009, tatu: Need to suppress serialization of a cyclic reference
                if (_isGroovyMetaClassGetter(am)) {
                    return null;
                }
            }
            return stdManglePropertyName(name, _getterPrefix.length());
        }
        return null;
    }

    @Override
    public String findNameForMutator(AnnotatedMethod am, String name)
    {
        if ((_mutatorPrefix != null) && name.startsWith(_mutatorPrefix)) {
            return stdManglePropertyName(name, _mutatorPrefix.length());
        }
        return null;
    }

    // Default implementation simply returns name as-is
    @Override
    public String modifyFieldName(AnnotatedField field, String name) {
        return name;
    }

    /*
    /**********************************************************************
    /* Name-mangling methods copied in 2.12 from "BeanUtil"
    /**********************************************************************
     */

    // 24-Sep-2017, tatu: note that "std" here refers to earlier (1.x, 2.x) distinction
    //   between "legacy" (slightly non-conforming) and "std" (fully conforming): with 3.x
    //   only latter exists.
    protected static String stdManglePropertyName(final String basename, final int offset)
    {
        final int end = basename.length();
        if (end == offset) { // empty name, nope
            return null;
        }
        // first: if it doesn't start with capital, return as-is
        char c0 = basename.charAt(offset);
        char c1 = Character.toLowerCase(c0);
        if (c0 == c1) {
            return basename.substring(offset);
        }
        // 17-Dec-2014, tatu: As per [databind#653], need to follow more
        //   closely Java Beans spec; specifically, if two first are upper-case,
        //   then no lower-casing should be done.
        if ((offset + 1) < end) {
            if (Character.isUpperCase(basename.charAt(offset+1))) {
                return basename.substring(offset);
            }
        }
        StringBuilder sb = new StringBuilder(end - offset);
        sb.append(c1);
        sb.append(basename, offset+1, end);
        return sb.toString();
    }

    /*
    /**********************************************************************
    /* Legacy methods moved in 2.12 from "BeanUtil" -- are these still needed?
    /**********************************************************************
     */

    // This method was added to address the need to weed out CGLib-injected
    // "getCallbacks" method. 
    // At this point caller has detected a potential getter method with
    // name "getCallbacks" and we need to determine if it is indeed injected
    // by Cglib. We do this by verifying that the  result type is "net.sf.cglib.proxy.Callback[]"

    // 05-Oct-2020, tatu: Removed from 3.0
    /*
    protected boolean _isCglibGetCallbacks(AnnotatedMethod am)
    {
        Class<?> rt = am.getRawType();
        // Ok, first: must return an array type
        if (rt.isArray()) {
            // And that type needs to be "net.sf.cglib.proxy.Callback".
            // Theoretically could just be a type that implements it, but
            // for now let's keep things simple, fix if need be.

            Class<?> compType = rt.getComponentType();
            // Actually, let's just verify it's a "net.sf.cglib.*" class/interface
            final String className = compType.getName();
            if (className.contains(".cglib")) {
                return className.startsWith("net.sf.cglib")
                    // also, as per [JACKSON-177]
                    || className.startsWith("org.hibernate.repackage.cglib")
                    // and [core#674]
                    || className.startsWith("org.springframework.cglib");
            }
        }
        return false;
    }
    */

    // 05-Oct-2020, tatu: Left in 3.0 for now
    // Another helper method to deal with Groovy's problematic metadata accessors
    protected boolean _isGroovyMetaClassGetter(AnnotatedMethod am) {
        return am.getRawType().getName().startsWith("groovy.lang");
    }

    /*
    /**********************************************************************
    /* Standard Provider implementation
    /**********************************************************************
     */

    /**
     * Provider for {@link DefaultAccessorNamingStrategy}.
     *<p>
     * Default instance will use following default prefixes:
     *<ul>
     * <li>Setter for regular POJOs: "set"
     *  </li>
     * <li>Builder-mutator: "with"
     *  </li>
     * <li>Regular getter: "get"
     *  </li>
     * <li>Is-getter (for Boolean values): "is"
     *  </li>
     * <ul>
     *<p>
     * 
     */
    public static class Provider
        extends AccessorNamingStrategy.Provider
        implements java.io.Serializable
    {
        private static final long serialVersionUID = 1L;

        protected final String _setterPrefix;
        protected final String _withPrefix;

        protected final String _getterPrefix;
        protected final String _isGetterPrefix;

        protected final boolean _allowLowerCaseFirstChar;
        protected final boolean _allowNonLetterFirstChar;

        public Provider() {
            this("set", JsonPOJOBuilder.DEFAULT_WITH_PREFIX,
                    "get", "is",
                    true, true);
        }

        protected Provider(Provider p,
                String setterPrefix, String withPrefix,
                String getterPrefix, String isGetterPrefix)
        {
            this(setterPrefix, withPrefix, getterPrefix, isGetterPrefix,
                    p._allowLowerCaseFirstChar, p._allowNonLetterFirstChar);
        }

        protected Provider(Provider p,
                boolean allowLowerCaseFirstChar, boolean allowNonLetterFirstChar)
        {
            this(p._setterPrefix, p._withPrefix,
                    p._getterPrefix, p._isGetterPrefix,
                    allowLowerCaseFirstChar, allowNonLetterFirstChar);
        }

        protected Provider(String setterPrefix, String withPrefix,
                String getterPrefix, String isGetterPrefix,
                boolean allowLowerCaseFirstChar, boolean allowNonLetterFirstChar)
        {
            _setterPrefix = setterPrefix;
            _withPrefix = withPrefix;
            _getterPrefix = getterPrefix;
            _isGetterPrefix = isGetterPrefix;
            _allowLowerCaseFirstChar = allowLowerCaseFirstChar;
            _allowNonLetterFirstChar = allowNonLetterFirstChar;
        }
        
        
        /**
         * Mutant factory for changing the prefix used for "setter"
         * methods
         *
         * @param prefix Prefix to use; or empty String {@code ""} to not use
         *   any prefix (meaning signature-compatible method name is used as
         *   the property basename (and subject to name mangling)),
         *   or {@code null} to prevent name-based detection.
         *
         * @return Provider instance with specified setter-prefix
         */
        public Provider withSetterPrefix(String prefix) {
            return new Provider(this,
                    prefix, _withPrefix, _getterPrefix, _isGetterPrefix);
        }
        
        /**
         * Mutant factory for changing the prefix used for Builders
         * (from default {@link JsonPOJOBuilder#DEFAULT_WITH_PREFIX})
         *
         * @param prefix Prefix to use; or empty String {@code ""} to not use
         *   any prefix (meaning signature-compatible method name is used as
         *   the property basename (and subject to name mangling)),
         *   or {@code null} to prevent name-based detection.
         *
         * @return Provider instance with specified with-prefix
         */
        public Provider withBuilderPrefix(String prefix) {
            return new Provider(this,
                    _setterPrefix, prefix, _getterPrefix, _isGetterPrefix);
        }

        /**
         * Mutant factory for changing the prefix used for "getter"
         * methods
         *
         * @param prefix Prefix to use; or empty String {@code ""} to not use
         *   any prefix (meaning signature-compatible method name is used as
         *   the property basename (and subject to name mangling)),
         *   or {@code null} to prevent name-based detection.
         *
         * @return Provider instance with specified getter-prefix
         */
        public Provider withGetterPrefix(String prefix) {
            return new Provider(this,
                    _setterPrefix, _withPrefix, prefix, _isGetterPrefix);
        }

        /**
         * Mutant factory for changing the prefix used for "is-getter"
         * methods (getters that return boolean/Boolean value).
         *
         * @param prefix Prefix to use; or empty String {@code ""} to not use
         *   any prefix (meaning signature-compatible method name is used as
         *   the property basename (and subject to name mangling)).
         *   or {@code null} to prevent name-based detection.
         *
         * @return Provider instance with specified is-getter-prefix
         */
        public Provider withIsGetterPrefix(String prefix) {
            return new Provider(this,
                    _setterPrefix, _withPrefix, _getterPrefix, prefix);
        }

        /**
         * Mutant factory for changing the rules regarding which characters
         * are allowed as the first character of property base name, after
         * checking and removing prefix.
         *<p>
         * For example, consider "getter" method candidate (no arguments, has return
         * type) named {@code getValue()} is considered, with "getter-prefix"
         * defined as {@code get}, then base name is {@code Value} and the
         * first character to consider is {@code V}. Upper-case letters are
         * always accepted so this is fine.
         * But with similar settings, method {@code get_value()} would only be
         * recognized as getter if {@code allowNonLetterFirstChar} is set to
         * {@code true}: otherwise it will not be considered a getter-method.
         * Similarly "is-getter" candidate method with name {@code island()}
         * would only be considered if {@code allowLowerCaseFirstChar} is set
         * to {@code true}.
         *
         * @param allowLowerCaseFirstChar Whether base names that start with lower-case
         *    letter (like {@code "a"} or {@code "b"}) are accepted as valid or not:
         *    consider difference between "setter-methods" {@code setValue()} and {@code setvalue()}.
         * @param allowNonLetterFirstChar  Whether base names that start with non-letter
         *    character (like {@code "_"} or number {@code 1}) are accepted as valid or not:
         *    consider difference between "setter-methods" {@code setValue()} and {@code set_value()}.
         *
         * @return Provider instance with specified is-getter-prefix
         */
        public Provider withFirstCharAcceptance(boolean allowLowerCaseFirstChar,
                boolean allowNonLetterFirstChar) {
            return new Provider(this,
                    allowLowerCaseFirstChar, allowNonLetterFirstChar);
        }
        
        @Override
        public AccessorNamingStrategy forPOJO(MapperConfig<?> config, AnnotatedClass targetClass)
        {
            return new DefaultAccessorNamingStrategy(config, targetClass,
                    _setterPrefix, _getterPrefix, _isGetterPrefix);
        }

        @Override
        public AccessorNamingStrategy forBuilder(MapperConfig<?> config,
                AnnotatedClass builderClass, BeanDescription valueTypeDesc)
        {
            AnnotationIntrospector ai = config.isAnnotationProcessingEnabled() ? config.getAnnotationIntrospector() : null;
            JsonPOJOBuilder.Value builderConfig = (ai == null) ? null : ai.findPOJOBuilderConfig(config, builderClass);
            String mutatorPrefix = (builderConfig == null) ? _withPrefix : builderConfig.withPrefix;
            return new DefaultAccessorNamingStrategy(config, builderClass,
                    mutatorPrefix, _getterPrefix, _isGetterPrefix);
        }

        @Override
        public AccessorNamingStrategy forRecord(MapperConfig<?> config, AnnotatedClass recordClass)
        {
            return new RecordNaming(config, recordClass);
        }
    }

    /**
     * Implementation used for supporting "non-prefix" naming convention of
     * Java 14 {@code java.lang.Record} types, and in particular find default
     * accessors for declared record fields.
     *<p>
     * Current / initial implementation will also recognize additional "normal"
     * getters ("get"-prefix) and is-getters ("is"-prefix and boolean return value)
     * by name.
     */
    public static class RecordNaming
        extends DefaultAccessorNamingStrategy
    {
        /**
         * Names of actual Record fields from definition; auto-detected.
         */
        protected final Set<String> _fieldNames;

        public RecordNaming(MapperConfig<?> config, AnnotatedClass forClass) {
            super(config, forClass,
                    // no setters for (immutable) Records:
                    null,
                    // trickier: regular fields are ok (handled differently), but should
                    // we also allow getter discovery? For now let's do so
                    "get", "is");
            _fieldNames = new HashSet<>();
            for (String name : JDK14Util.getRecordFieldNames(forClass.getRawType())) {
                _fieldNames.add(name);
            }
        }

        @Override
        public String findNameForRegularGetter(AnnotatedMethod am, String name)
        {
            // By default, field names are un-prefixed, but verify so that we will not
            // include "toString()" or additional custom methods (unless latter are
            // annotated for inclusion)
            if (_fieldNames.contains(name)) {
                return name;
            }
            // but also allow auto-detecting additional getters, if any?
            return super.findNameForRegularGetter(am, name);
        }
    }
}
