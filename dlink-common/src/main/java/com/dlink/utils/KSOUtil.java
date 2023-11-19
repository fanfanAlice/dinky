/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.dlink.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Strings;

public class KSOUtil {

    public static final String KSO_FLINK_ENCRYPT_KEY = "kso.flink.encrypt-key";
    public static final String KSO_DEFAULT_ENCRYPT_KEY = "password";

    public static String getDecryptedValue(String statement, String flinkEncryptKey) {
        if (Strings.isNullOrEmpty(statement)) {
            return statement;
        }
        flinkEncryptKey = String.join("|", flinkEncryptKey.split(","));
        Pattern flinkEncryptKeyPattern = Pattern.compile("'(" + flinkEncryptKey + ")'\\s*=\\s*'(?<value>.*)'");
        Matcher matcher = flinkEncryptKeyPattern.matcher(statement);
        while (matcher.find()) {
            String value = matcher.group("value");
            String password = EncryptUtil.getDecryptedValue(value.replace("'", ""));
            if (password == null) {
                throw new RuntimeException("decryptPassword decrypted is null :" + value);
            } else {
                statement = statement.replace("'" + value + "'", "'" + password + "'");
            }
        }
        return statement;
    }
}
