/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.webmonitor.handlers;

import org.apache.flink.runtime.executiongraph.ExecutionGraph;
import org.apache.flink.runtime.webmonitor.ExecutionGraphHolder;

import java.util.Map;

/**
 * Request handler that returns the JSON program plan of a job graph.
 */
public class JobVertexDetailsHandler extends AbstractExecutionGraphRequestHandler implements RequestHandler.JsonResponse {

	
	public JobVertexDetailsHandler(ExecutionGraphHolder executionGraphHolder) {
		super(executionGraphHolder);
	}

	@Override
	public String handleRequest(ExecutionGraph graph, Map<String, String> params) throws Exception {
		String vertexId = params.get(Parameters.JOB_VERTEX_ID);
		
		return "vertex: " + vertexId;
	}
}
