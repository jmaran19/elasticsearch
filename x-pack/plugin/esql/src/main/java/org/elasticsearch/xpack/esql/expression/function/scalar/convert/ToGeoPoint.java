/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.scalar.convert;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.compute.ann.ConvertEvaluator;
import org.elasticsearch.xpack.esql.expression.function.FunctionInfo;
import org.elasticsearch.xpack.esql.expression.function.Param;
import org.elasticsearch.xpack.ql.expression.Expression;
import org.elasticsearch.xpack.ql.tree.NodeInfo;
import org.elasticsearch.xpack.ql.tree.Source;
import org.elasticsearch.xpack.ql.type.DataType;

import java.util.List;
import java.util.Map;

import static org.elasticsearch.xpack.esql.type.EsqlDataTypes.GEO_POINT;
import static org.elasticsearch.xpack.ql.type.DataTypes.KEYWORD;
import static org.elasticsearch.xpack.ql.type.DataTypes.LONG;
import static org.elasticsearch.xpack.ql.type.DataTypes.TEXT;
import static org.elasticsearch.xpack.ql.util.SpatialCoordinateTypes.GEO;

public class ToGeoPoint extends AbstractConvertFunction {

    private static final Map<DataType, BuildFactory> EVALUATORS = Map.ofEntries(
        Map.entry(GEO_POINT, (fieldEval, source) -> fieldEval),
        Map.entry(LONG, ToGeoPointFromLongEvaluator.Factory::new),
        Map.entry(KEYWORD, ToGeoPointFromStringEvaluator.Factory::new),
        Map.entry(TEXT, ToGeoPointFromStringEvaluator.Factory::new)
    );

    @FunctionInfo(returnType = "geo_point")
    public ToGeoPoint(Source source, @Param(name = "v", type = { "geo_point", "long", "keyword", "text" }) Expression field) {
        super(source, field);
    }

    @Override
    protected Map<DataType, BuildFactory> factories() {
        return EVALUATORS;
    }

    @Override
    public DataType dataType() {
        return GEO_POINT;
    }

    @Override
    public Expression replaceChildren(List<Expression> newChildren) {
        return new ToGeoPoint(source(), newChildren.get(0));
    }

    @Override
    protected NodeInfo<? extends Expression> info() {
        return NodeInfo.create(this, ToGeoPoint::new, field());
    }

    @ConvertEvaluator(extraName = "FromString", warnExceptions = { IllegalArgumentException.class })
    static BytesRef fromKeyword(BytesRef in) {
        return GEO.stringAsWKB(in.utf8ToString());
    }

    @ConvertEvaluator(extraName = "FromLong", warnExceptions = { IllegalArgumentException.class })
    static BytesRef fromLong(long encoded) {
        return GEO.longAsWKB(encoded);
    }
}
