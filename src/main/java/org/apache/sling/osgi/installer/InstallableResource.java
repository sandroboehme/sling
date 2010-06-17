/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.osgi.installer;

import java.io.InputStream;
import java.util.Dictionary;

/** A piece of data that can be installed by the OSGi controller.
 * 	Wraps either a Dictionary or an InputStream.
 *  Extension is used to decide which type of data (bundle, config, etc.).
 */
public interface InstallableResource {

	/** Return this data's URL. It is opaque for the {@link OsgiInstaller}
	 * 	but the scheme must be the one used in the
	 * 	{@link OsgiInstaller#registerResources} call.
	 */
    String getUrl();

	/** Return this resource's extension, based on its URL */
    String getExtension();

	/** Return an input stream with the data of this resource. Null if resource
	 *  contains a dictionary instead. Caller is responsible for closing the stream.
	 */
    InputStream getInputStream();

	/** Return this resource's dictionary. Null if resource contains an InputStream instead */
	Dictionary<String, Object> getDictionary();

	/** Return this resource's digest. Not necessarily an actual md5 or other digest of the
	 *  data, can be any string that changes if the data changes.
	 */
    String getDigest();

	/** Return the priority of this resource. Priorities are used to decide which
	 *  resource to install when several are registered for the same OSGi entity
	 *  (bundle, config, etc.)
	 */
    int getPriority();
}
