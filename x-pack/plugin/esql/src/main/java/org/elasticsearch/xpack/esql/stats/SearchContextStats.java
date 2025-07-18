/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.stats;

import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;
import org.elasticsearch.index.mapper.ConstantFieldType;
import org.elasticsearch.index.mapper.DocCountFieldMapper.DocCountFieldType;
import org.elasticsearch.index.mapper.IdFieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.NumberFieldMapper.NumberFieldType;
import org.elasticsearch.index.mapper.SeqNoFieldMapper;
import org.elasticsearch.index.mapper.TextFieldMapper;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.xpack.esql.EsqlIllegalArgumentException;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute.FieldName;
import org.elasticsearch.xpack.esql.core.util.Holder;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.index.mapper.DataStreamTimestampFieldMapper.TimestampFieldType;
import static org.elasticsearch.index.mapper.DateFieldMapper.DateFieldType;
import static org.elasticsearch.index.mapper.KeywordFieldMapper.KeywordFieldType;

/**
 * This class provides <code>SearchStats</code> from a list of <code>SearchExecutionContext</code>'s.
 * It contains primarily a cache of <code>FieldStats</code> which is dynamically updated as needed.
 * Each <code>FieldStats</code> contains <code>FieldConfig</code> information which is populated once at creation time.
 * The remaining statistics are lazily computed and cached only on demand.
 * This cache is not thread-safe.
 */
public class SearchContextStats implements SearchStats {

    private final List<SearchExecutionContext> contexts;

    private record FieldConfig(boolean exists, boolean hasExactSubfield, boolean indexed, boolean hasDocValues, MappedFieldType fieldType) {
        FieldConfig(boolean exists, boolean hasExactSubfield, boolean indexed, boolean hasDocValues) {
            this(exists, hasExactSubfield, indexed, hasDocValues, null);
        }
    }

    private static class FieldStats {
        private Long count;
        private Object min, max;
        private Boolean singleValue;
        private FieldConfig config;
    }

    private static final int CACHE_SIZE = 32;

    // simple non-thread-safe cache for avoiding unnecessary IO (which while fast is still I/O)
    private final Map<String, FieldStats> cache = new LinkedHashMap<>(CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, FieldStats> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    public static SearchStats from(List<SearchExecutionContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return SearchStats.EMPTY;
        }
        return new SearchContextStats(contexts);
    }

    private SearchContextStats(List<SearchExecutionContext> contexts) {
        this.contexts = contexts;
        assert contexts != null && contexts.isEmpty() == false;
    }

    private FieldStats makeFieldStats(String field) {
        var stat = new FieldStats();
        stat.config = makeFieldConfig(field);
        return stat;
    }

    private FieldConfig makeFieldConfig(String field) {
        boolean exists = false;
        boolean hasExactSubfield = true;
        boolean indexed = true;
        boolean hasDocValues = true;
        boolean mixedFieldType = false;
        MappedFieldType fieldType = null; // Extract the field type, it will be used by min/max later.
        // even if there are deleted documents, check the existence of a field
        // since if it's missing, deleted documents won't change that
        for (SearchExecutionContext context : contexts) {
            if (context.isFieldMapped(field)) {
                MappedFieldType type = context.getFieldType(field);
                if (fieldType == null) {
                    fieldType = type;
                } else if (mixedFieldType == false && fieldType.typeName().equals(type.typeName()) == false) {
                    mixedFieldType = true;
                }
                exists |= true;
                indexed &= type.isIndexed();
                hasDocValues &= type.hasDocValues();
                hasExactSubfield &= type instanceof TextFieldMapper.TextFieldType t && t.canUseSyntheticSourceDelegateForQuerying();
            } else {
                indexed = false;
                hasDocValues = false;
                hasExactSubfield = false;
            }
            if (exists && indexed == false && hasDocValues == false && hasExactSubfield == false) {
                break;
            }
        }
        if (exists == false) {
            // if it does not exist on any context, no other settings are valid
            return new FieldConfig(false, false, false, false);
        } else {
            return new FieldConfig(exists, hasExactSubfield, indexed, hasDocValues, mixedFieldType ? null : fieldType);
        }
    }

    private boolean fastNoCacheFieldExists(String field) {
        for (SearchExecutionContext context : contexts) {
            if (context.isFieldMapped(field)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean exists(FieldName field) {
        var stat = cache.get(field.string());
        return stat != null ? stat.config.exists : fastNoCacheFieldExists(field.string());
    }

    @Override
    public boolean isIndexed(FieldName field) {
        return cache.computeIfAbsent(field.string(), this::makeFieldStats).config.indexed;
    }

    @Override
    public boolean hasDocValues(FieldName field) {
        return cache.computeIfAbsent(field.string(), this::makeFieldStats).config.hasDocValues;
    }

    @Override
    public boolean hasExactSubfield(FieldName field) {
        return cache.computeIfAbsent(field.string(), this::makeFieldStats).config.hasExactSubfield;
    }

    @Override
    public long count() {
        var count = new long[] { 0 };
        boolean completed = doWithContexts(r -> {
            count[0] += r.numDocs();
            return true;
        }, false);
        return completed ? count[0] : -1;
    }

    @Override
    public long count(FieldName field) {
        var stat = cache.computeIfAbsent(field.string(), this::makeFieldStats);
        if (stat.count == null) {
            var count = new long[] { 0 };
            boolean completed = doWithContexts(r -> {
                count[0] += countEntries(r, field.string());
                return true;
            }, false);
            stat.count = completed ? count[0] : -1;
        }
        return stat.count;
    }

    @Override
    public long count(FieldName field, BytesRef value) {
        var count = new long[] { 0 };
        Term term = new Term(field.string(), value);
        boolean completed = doWithContexts(r -> {
            count[0] += r.docFreq(term);
            return true;
        }, false);
        return completed ? count[0] : -1;
    }

    @Override
    public Object min(FieldName field) {
        var stat = cache.computeIfAbsent(field.string(), this::makeFieldStats);
        // Consolidate min for indexed date fields only, skip the others and mixed-typed fields.
        MappedFieldType fieldType = stat.config.fieldType;
        if (fieldType == null || stat.config.indexed == false || fieldType instanceof DateFieldType == false) {
            return null;
        }
        if (stat.min == null) {
            var min = new long[] { Long.MAX_VALUE };
            Holder<Boolean> foundMinValue = new Holder<>(false);
            doWithContexts(r -> {
                byte[] minPackedValue = PointValues.getMinPackedValue(r, field.string());
                if (minPackedValue != null && minPackedValue.length == 8) {
                    long minValue = NumericUtils.sortableBytesToLong(minPackedValue, 0);
                    if (minValue <= min[0]) {
                        min[0] = minValue;
                        foundMinValue.set(true);
                    }
                }
                return true;
            }, true);
            stat.min = foundMinValue.get() ? min[0] : null;
        }
        return stat.min;
    }

    @Override
    public Object max(FieldName field) {
        var stat = cache.computeIfAbsent(field.string(), this::makeFieldStats);
        // Consolidate max for indexed date fields only, skip the others and mixed-typed fields.
        MappedFieldType fieldType = stat.config.fieldType;
        if (fieldType == null || stat.config.indexed == false || fieldType instanceof DateFieldType == false) {
            return null;
        }
        if (stat.max == null) {
            var max = new long[] { Long.MIN_VALUE };
            Holder<Boolean> foundMaxValue = new Holder<>(false);
            doWithContexts(r -> {
                byte[] maxPackedValue = PointValues.getMaxPackedValue(r, field.string());
                if (maxPackedValue != null && maxPackedValue.length == 8) {
                    long maxValue = NumericUtils.sortableBytesToLong(maxPackedValue, 0);
                    if (maxValue >= max[0]) {
                        max[0] = maxValue;
                        foundMaxValue.set(true);
                    }
                }
                return true;
            }, true);
            stat.max = foundMaxValue.get() ? max[0] : null;
        }
        return stat.max;
    }

    @Override
    public boolean isSingleValue(FieldName field) {
        String fieldName = field.string();
        var stat = cache.computeIfAbsent(fieldName, this::makeFieldStats);
        if (stat.singleValue == null) {
            // there's no such field so no need to worry about multi-value fields
            if (stat.config.exists == false) {
                stat.singleValue = true;
            } else {
                // fields are MV per default
                var sv = new boolean[] { false };
                for (SearchExecutionContext context : contexts) {
                    MappedFieldType mappedType = context.isFieldMapped(fieldName) ? context.getFieldType(fieldName) : null;
                    if (mappedType != null) {
                        sv[0] = true;
                        doWithContexts(r -> {
                            sv[0] &= detectSingleValue(r, mappedType, fieldName);
                            return sv[0];
                        }, true);
                        break;
                    }
                }
                stat.singleValue = sv[0];
            }
        }
        return stat.singleValue;
    }

    private boolean detectSingleValue(IndexReader r, MappedFieldType fieldType, String name) throws IOException {
        // types that are always single value (and are accessible through instanceof)
        if (fieldType instanceof ConstantFieldType || fieldType instanceof DocCountFieldType || fieldType instanceof TimestampFieldType) {
            return true;
        }

        var typeName = fieldType.typeName();

        // non-visible fields, check their names
        boolean found = switch (typeName) {
            case IdFieldMapper.NAME, SeqNoFieldMapper.NAME -> true;
            default -> false;
        };

        if (found) {
            return true;
        }

        // check against doc size
        DocCountTester tester = null;
        if (fieldType instanceof DateFieldType || fieldType instanceof NumberFieldType) {
            tester = lr -> {
                PointValues values = lr.getPointValues(name);
                return values == null || values.size() == values.getDocCount();
            };
        } else if (fieldType instanceof KeywordFieldType) {
            tester = lr -> {
                Terms terms = lr.terms(name);
                return terms == null || terms.size() == terms.getDocCount();
            };
        }

        if (tester != null) {
            // check each leaf
            for (LeafReaderContext context : r.leaves()) {
                if (tester.test(context.reader()) == false) {
                    return false;
                }
            }
            // field is missing or single value
            return true;
        }

        // unsupported type - default to MV
        return false;
    }

    @Override
    public boolean canUseEqualityOnSyntheticSourceDelegate(FieldAttribute.FieldName name, String value) {
        for (SearchExecutionContext ctx : contexts) {
            MappedFieldType type = ctx.getFieldType(name.string());
            if (type == null) {
                return false;
            }
            if (type instanceof TextFieldMapper.TextFieldType t) {
                if (t.canUseSyntheticSourceDelegateForQueryingEquality(value) == false) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
    }

    @Override
    public String constantValue(FieldAttribute.FieldName name) {
        String val = null;
        for (SearchExecutionContext ctx : contexts) {
            MappedFieldType f = ctx.getFieldType(name.string());
            if (f == null) {
                return null;
            }
            if (f instanceof ConstantFieldType cf) {
                var fetcher = cf.valueFetcher(ctx, null);
                String thisVal = null;
                try {
                    // since the value is a constant, the doc _should_ be irrelevant
                    List<Object> vals = fetcher.fetchValues(null, -1, null);
                    Object objVal = vals.size() == 1 ? vals.get(0) : null;
                    // we are considering only string values for now, since this can return "strange" things,
                    // see IndexModeFieldType
                    thisVal = objVal instanceof String ? (String) objVal : null;
                } catch (IOException iox) {}

                if (thisVal == null) {
                    // Value not yet set
                    return null;
                }
                if (val == null) {
                    val = thisVal;
                } else if (thisVal.equals(val) == false) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return val;
    }

    private interface DocCountTester {
        Boolean test(LeafReader leafReader) throws IOException;
    }

    //
    // @see org.elasticsearch.search.query.QueryPhaseCollectorManager#shortcutTotalHitCount(IndexReader, Query)
    //
    private static long countEntries(IndexReader indexReader, String field) {
        long count = 0;
        try {
            for (LeafReaderContext context : indexReader.leaves()) {
                LeafReader reader = context.reader();
                FieldInfos fieldInfos = reader.getFieldInfos();
                FieldInfo fieldInfo = fieldInfos.fieldInfo(field);

                if (fieldInfo != null) {
                    if (fieldInfo.getDocValuesType() == DocValuesType.NONE) {
                        // no shortcut possible: it's a text field, empty values are counted as no value.
                        return -1;
                    }
                    if (fieldInfo.getPointIndexDimensionCount() > 0) {
                        PointValues points = reader.getPointValues(field);
                        if (points != null) {
                            count += points.size();
                        }
                    } else if (fieldInfo.getIndexOptions() != IndexOptions.NONE) {
                        Terms terms = reader.terms(field);
                        if (terms != null) {
                            count += terms.getSumTotalTermFreq();
                        }
                    } else {
                        return -1; // no shortcut possible for fields that are not indexed
                    }
                }
            }
        } catch (IOException ex) {
            throw new EsqlIllegalArgumentException("Cannot access data storage", ex);
        }
        return count;
    }

    private interface IndexReaderConsumer {
        /**
         * Returns true if the consumer should keep on going, false otherwise.
         */
        boolean consume(IndexReader reader) throws IOException;
    }

    private boolean doWithContexts(IndexReaderConsumer consumer, boolean acceptsDeletions) {
        try {
            for (SearchExecutionContext context : contexts) {
                for (LeafReaderContext leafContext : context.searcher().getLeafContexts()) {
                    var reader = leafContext.reader();
                    if (acceptsDeletions == false && reader.hasDeletions()) {
                        return false;
                    }
                    // check if the looping continues or not
                    if (consumer.consume(reader) == false) {
                        return false;
                    }
                }
            }
            return true;
        } catch (IOException ex) {
            throw new EsqlIllegalArgumentException("Cannot access data storage", ex);
        }
    }
}
