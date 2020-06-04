/*
 * Copyright 2019 liaochong
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.liaochong.myexcel.core;

import com.github.liaochong.myexcel.core.constant.AllConverter;
import com.github.liaochong.myexcel.core.constant.CsvConverter;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * @author liaochong
 * @version 1.0
 */
public class ConvertContext {
    /**
     * {@link com.github.liaochong.myexcel.core.annotation.ExcelModel} setting
     */
    private Configuration configuration = new Configuration();

    /**
     * {@link com.github.liaochong.myexcel.core.annotation.ExcelColumn} mapping
     */
    private Map<Field, ExcelColumnMapping> excelColumnMappingMap = new HashMap<>();
    /**
     * csv or excel
     */
    private Class converterType;

    private boolean isConvertCsv;

    public ConvertContext(boolean isConvertCsv) {
        this.isConvertCsv = isConvertCsv;
        this.converterType = isConvertCsv ? CsvConverter.class : AllConverter.class;
    }

    public Configuration getConfiguration() {
        return this.configuration;
    }

    public Map<Field, ExcelColumnMapping> getExcelColumnMappingMap() {
        return this.excelColumnMappingMap;
    }

    public Class getConverterType() {
        return this.converterType;
    }

    public boolean isConvertCsv() {
        return this.isConvertCsv;
    }

    public void setConfiguration(Configuration configuration) {
        this.configuration = configuration;
    }

    public void setExcelColumnMappingMap(Map<Field, ExcelColumnMapping> excelColumnMappingMap) {
        this.excelColumnMappingMap = excelColumnMappingMap;
    }

    public void setConverterType(Class converterType) {
        this.converterType = converterType;
    }

    public void setConvertCsv(boolean isConvertCsv) {
        this.isConvertCsv = isConvertCsv;
    }
}
