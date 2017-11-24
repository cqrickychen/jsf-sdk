/**
 * Copyright 2004-2048 .
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ipd.jsf.gd.transport;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ipd.jsf.gd.error.JSFCodecException;
import com.ipd.jsf.gd.util.Constants;

/**
 * Created on 14-3-26.
 */
public class ServerTransportFactory {

    public static Map<String,ServerTransport> serverTransportMap= new ConcurrentHashMap<String,ServerTransport>();

    /*
     *
     */
    public static ServerTransport getServerTransport(ServerTransportConfig serverConfig){
        ServerTransport transport = null;
        Constants.ProtocolType protocolTypeId = serverConfig.getProtocolType();
        switch (protocolTypeId){
            case jsf:
                transport = new JSFServerTransport(serverConfig);
                break;
            case dubbo:
                transport = new JSFServerTransport(serverConfig);
                break;
            default:
                throw new JSFCodecException("protocolTypeId "+protocolTypeId+" can not support check you config!!");
        }
		if (transport != null) {
			String key = Integer.toString(serverConfig.getPort());
			serverTransportMap.put(key, transport);
		}
		return transport;
    }

    public ServerTransport getServerTransportByKey(String key){
        return serverTransportMap.get(key);
    }
}