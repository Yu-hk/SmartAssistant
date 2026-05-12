/*
 * Copyright (c) 2025-2026 SmartAssistant Project. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for
 * full license information.
 */

package com.example.smartassistant.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * PostgreSQL 向量类型处理器
 * 将 float[] 转换为 pgvector 的 VECTOR 类型
 */
public class FloatArrayTypeHandler extends BaseTypeHandler<float[]> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, float[] parameter, JdbcType jdbcType) throws SQLException {
        PGobject pgObject = new PGobject();
        pgObject.setType("vector");
        pgObject.setValue(arrayToString(parameter));
        ps.setObject(i, pgObject);
    }

    @Override
    public float[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parseVectorString(rs.getString(columnName));
    }

    @Override
    public float[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parseVectorString(rs.getString(columnIndex));
    }

    @Override
    public float[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parseVectorString(cs.getString(columnIndex));
    }

    /**
     * float[] 转 VECTOR 字符串，如 [0.1,0.2,0.3]
     */
    private String arrayToString(float[] array) {
        if (array == null || array.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < array.length; i++) {
            sb.append(array[i]);
            if (i < array.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * VECTOR 字符串转 float[]
     */
    private float[] parseVectorString(String str) {
        if (str == null || str.isEmpty()) {
            return new float[0];
        }
        // 去掉首尾的 [ 和 ]
        str = str.trim();
        if (str.startsWith("[")) {
            str = str.substring(1);
        }
        if (str.endsWith("]")) {
            str = str.substring(0, str.length() - 1);
        }
        if (str.isEmpty()) {
            return new float[0];
        }
        String[] parts = str.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].trim());
        }
        return result;
    }
}
