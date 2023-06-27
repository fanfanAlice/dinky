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

import static com.dlink.utils.KSOUtil.KSO_FLINK_ENCRYPT_KEY;

import com.dlink.service.FragmentVariableService;

import java.util.Map;

public class KSOConfig {

    public static Map<String, String> getEnabledVariables(FragmentVariableService fragmentVariableService) {
        return fragmentVariableService.listEnabledVariables();
    }

    public static String getKSOFlinkEncryptKey(FragmentVariableService service) {
        String encryptKey = getEnabledVariables(service).getOrDefault(KSO_FLINK_ENCRYPT_KEY, "password");
        return encryptKey;
    }
}
