/* Copyright © "Open Digital Education", 2014
 *
 * This program is published by "Open Digital Education".
 * You must indicate the name of the software and the company in any production /contribution
 * using the software and indicate on the home page of the software industry in question,
 * "powered by Open Digital Education" with a reference to the website: https://opendigitaleducation.com/.
 *
 * This program is free software, licensed under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, version 3 of the License.
 *
 * You can redistribute this application and/or modify it since you respect the terms of the GNU Affero General Public License.
 * If you modify the source code and then use this modified source code in your creation, you must make available the source code of your modifications.
 *
 * You should have received a copy of the GNU Affero General Public License along with the software.
 * If not, please see : <http://www.gnu.org/licenses/>. Full compliance requires reading the terms of this license and following its directives.

 *
 */

package org.entcore.session;

import fr.wseduc.mongodb.MongoDb;
import io.vertx.core.shareddata.LocalMap;
import org.entcore.common.neo4j.Neo4j;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.utils.StringUtils;
import org.vertx.java.busmods.BusModBase;

import java.util.*;

import static fr.wseduc.webutils.Utils.getOrElse;

public class AuthManager extends BusModBase implements Handler<Message<JsonObject>> {

	public static final String SESSIONS_COLLECTION = "sessions";

	protected MongoDb mongo;
	protected Neo4j neo4j;
	protected SessionStore sessionStore;
	protected Boolean cluster;

	public void start() {
		super.start();
		LocalMap<Object, Object> server = vertx.sharedData().getLocalMap("server");

		String neo4jConfig = (String) server.get("neo4jConfig");
		neo4j = Neo4j.getInstance();
		neo4j.init(vertx, new JsonObject(neo4jConfig));

		cluster = (Boolean) server.get("cluster");
		String node = (String) server.get("node");
		mongo = MongoDb.getInstance();
		mongo.init(vertx.eventBus(), node + config.getString("mongo-address", "wse.mongodb.persistor"));

		sessionStore = new MapSessionStore(vertx, cluster, config);

		final String address = getOptionalStringConfig("address", "wse.session");
		eb.localConsumer(address, this);
	}

	@Override
	public void handle(Message<JsonObject> message) {
		String action = message.body().getString("action");

		if (action == null) {
			sendError(message, "action must be specified");
			return;
		}

		switch (action) {
		case "find":
			doFind(message);
			break;
		case "findByUserId":
			doFindByUserId(message);
			break;
		case "create":
			doCreate(message);
			break;
		case "drop":
			doDrop(message);
			break;
		case "dropCacheSession":
			doDropCacheSession(message);
			break;
		case "dropPermanentSessions" :
			doDropPermanentSessions(message);
			break;
		case "addAttribute":
			doAddAttribute(message);
			break;
		case "removeAttribute":
			doRemoveAttribute(message);
			break;
		default:
			sendError(message, "Invalid action: " + action);
		}
	}

	private void doDropCacheSession(Message<JsonObject> message) {
		final String userId = message.body().getString("userId");
		if (userId == null || userId.trim().isEmpty()) {
			sendError(message, "[doDropCacheSession] Invalid userId : " + message.body().encode());
			return;
		}
		final boolean deleteSessionCollection = getOrElse(message.body().getBoolean("deleteSessionCollection"), true);
		sessionStore.listSessionsIds(userId, ar -> {
			if (ar.succeeded()) {
				for (Object sessionId : ar.result()) {
					if (sessionId instanceof String) {
						dropSession(null, (String) sessionId, null, deleteSessionCollection);
					}
				}
				sendOK(message);
			} else {
				logger.error("[doDropCacheSession] error when list sessions ids with userId : " + userId, ar.cause());
				sendError(message, "[doDropCacheSession] Invalid userId : " + userId);
			}
		});
	}

	private void doDropPermanentSessions(final Message<JsonObject> message) {
		String userId = message.body().getString("userId");
		String currentSessionId = message.body().getString("currentSessionId");
		if (userId == null || userId.trim().isEmpty()) {
			sendError(message, "[doDropPermanentSessions] Invalid userId : " + message.body().encode());
			return;
		}

		JsonObject query = new JsonObject().put("userId", userId);
		if (currentSessionId != null) {
			query.put("_id", new JsonObject().put("$ne", currentSessionId));
		}
		mongo.delete(SESSIONS_COLLECTION, query, new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> event) {
				if ("ok".equals(event.body().getString("status"))) {
					sendOK(message);
				} else {
					sendError(message, event.body().getString("message"));
				}
			}
		});
	}

	private void doFindByUserId(final Message<JsonObject> message) {
		final String userId = message.body().getString("userId");
		if (userId == null || userId.trim().isEmpty()) {
			sendError(message, "[doFindByUserId] Invalid userId : " + message.body().encode());
			return;
		}

		sessionStore.getSessionByUserId(userId, ar -> {
			if (ar.succeeded()) {
				sendOK(message, new JsonObject().put("status", "ok").put("session", ar.result()));
			} else if (getOrElse(message.body().getBoolean("allowDisconnectedUser"), false)) {
				generateSessionInfos(userId, new Handler<JsonObject>() {

					@Override
					public void handle(JsonObject infos) {
						if (infos != null) {
							sendOK(message, new JsonObject().put("status", "ok")
									.put("session", infos));
						} else {
							sendError(message, "Invalid userId : " + userId);
						}
					}
				});
			} else {
				sendError(message, "[doFindByUserId] info is null - Invalid userId : " + message.body().encode());
			}
		});
	}

	private void doFind(final Message<JsonObject> message) {
		final String sessionId = message.body().getString("sessionId");
		if (sessionId == null || sessionId.trim().isEmpty()) {
			sendError(message, "Invalid sessionId.");
			return;
		}

		sessionStore.getSession(sessionId, ar -> {
			if (ar.succeeded()) {
				sendOK(message, new JsonObject().put("status", "ok").put("session", ar.result()));
			} else {
				final JsonObject query = new JsonObject().put("_id", sessionId);
				mongo.findOne(SESSIONS_COLLECTION, query, event -> {
					JsonObject res = event.body().getJsonObject("result");
					String userId;
					if ("ok".equals(event.body().getString("status")) && res != null &&
							(userId = res.getString("userId")) != null && !userId.trim().isEmpty()) {
						final String uId = userId;
						final boolean secureLocation = getOrElse(res.getBoolean("secureLocation"), false);
						createSession(userId, sessionId, res.getString("SessionIndex"), res.getString("NameID"), secureLocation,
								sId -> {
									if (sId != null) {
										sessionStore.getSession(sId, ar2 -> {
											if (ar2.succeeded()) {
												JsonObject s = ar2.result();
												if (s != null) {
													JsonObject sessionResponse = new JsonObject().put("status", "ok")
															.put("session", s);
													sendOK(message, sessionResponse);
												} else {
													sendError(message, "Session not found. 1");
												}
											} else {
												generateSessionInfos(uId, event1 -> {
													if (event1 != null) {
														logger.info("Session with store problem : " + event1.encode());
														sendOK(message, new JsonObject().put("status", "ok")
																.put("session", event1));
													} else {
														sendError(message, "Session not found. 2");
													}
												});
											}
										});
									} else {
										sendError(message, "Session not found. 3");
									}
								});
					} else {
						sendError(message, "Session not found. 4");
					}
				});
			}
		});
	}

	private void doCreate(final Message<JsonObject> message) {
		final String userId = message.body().getString("userId");
		final String sessionIndex = message.body().getString("SessionIndex");
		final String nameID = message.body().getString("NameID");
		final boolean secureLocation = getOrElse(message.body().getBoolean("secureLocation"), false);
		if (userId == null || userId.trim().isEmpty()) {
			sendError(message, "[doCreate] Invalid userId : " + message.body().encode());
			return;
		}

		createSession(userId, null, sessionIndex, nameID, secureLocation, sessionId -> {
			if (sessionId != null) {
				sendOK(message, new JsonObject()
						.put("status", "ok")
						.put("sessionId", sessionId));
			} else {
				sendError(message, "Invalid userId : " + userId);
			}
		});
	}

	private void createSession(final String userId, final String sId, final String sessionIndex, final String nameId,
			final boolean secureLocation, final Handler<String> handler) {
		final String sessionId = (sId != null) ? sId : UUID.randomUUID().toString();
		generateSessionInfos(userId, new Handler<JsonObject>() {

			@Override
			public void handle(JsonObject infos) {
				if (infos != null) {
					sessionStore.putSession(userId, sessionId, infos, secureLocation, ar -> {
						if (ar.failed()) {
							logger.error("Error putting session in store", ar.cause());
						}

						// TODO verify logs and verify if add else clause isn't necessarry (converse old fonctionnality)
						final JsonObject now = MongoDb.now();
						if (sId == null) {
							JsonObject json = new JsonObject()
									.put("_id", sessionId).put("userId", userId)
									.put("created", now).put("lastUsed", now);
							if (sessionIndex != null && nameId != null) {
								json.put("SessionIndex", sessionIndex).put("NameID", nameId);
							}
							if (secureLocation) {
								json.put("secureLocation", secureLocation);
							}
							mongo.save(SESSIONS_COLLECTION, json);
						} else {
							mongo.update(SESSIONS_COLLECTION, new JsonObject().put("_id", sessionId),
									new JsonObject().put("$set", new JsonObject().put("lastUsed", now)));
						}
						handler.handle(sessionId);
					});
				} else {
					handler.handle(null);
				}
			}
		});
	}

	private void doDrop(final Message<JsonObject> message) {
		final String sessionId = message.body().getString("sessionId");
		boolean sessionMeta =  getOrElse(message.body().getBoolean("sessionMetadata"), false);
		if (sessionId == null || sessionId.trim().isEmpty()) {
			sendError(message, "Invalid sessionId.");
			return;
		}

		if (sessionMeta) {
			final JsonObject query = new JsonObject().put("_id", sessionId);
			mongo.findOne(SESSIONS_COLLECTION, query, new Handler<Message<JsonObject>>() {
				@Override
				public void handle(Message<JsonObject> event) {
					JsonObject res = event.body().getJsonObject("result");
					dropSession(message, sessionId, res);
				}
			});
		} else {
			dropSession(message, sessionId, null);
		}
	}

	private void dropSession(Message<JsonObject> message, String sessionId, JsonObject meta) {
		dropSession(message, sessionId, meta, true);
	}

	private void dropSession(Message<JsonObject> message, String sessionId, JsonObject meta, boolean deleteSessionCollection) {
		if (deleteSessionCollection) {
			mongo.delete(SESSIONS_COLLECTION, new JsonObject().put("_id", sessionId));
		}

		sessionStore.dropSession(sessionId, ar -> {
			if (ar.succeeded()) {
				if (getOrElse(config.getBoolean("slo"), false)) {
					final String userId = ar.result().getString("userId");
					eb.send("cas", new JsonObject().put("action", "logout").put("userId", userId));
				}
			} else {
				logger.error("In doDrop - Error getting object after removing hazelcast session " + sessionId, ar.cause());
			}
			JsonObject res = new JsonObject().put("status", "ok");
			if (meta != null) {
				res.put("sessionMetadata", meta);
			}
			if (message != null) {
				sendOK(message, res);
			}
		});
	}

	private void doAddAttribute(Message<JsonObject> message) {
		final String userId = message.body().getString("userId");
		if (userId == null || userId.trim().isEmpty()) {
			sendError(message, "[getSessionByUserId] Invalid userId : " + message.body().encode());
			return;
		}
		String key = message.body().getString("key");
		if (key == null || key.trim().isEmpty()) {
			sendError(message, "Invalid key.");
			return;
		}

		Object value = message.body().getValue("value");
		if (value == null || (!(value instanceof String) && !(value instanceof Long) && !(value instanceof JsonObject))) {
			sendError(message, "Invalid value.");
			return;
		}

		sessionStore.addCacheAttributeByUserId(userId, key, value, ar -> {
			if (ar.succeeded()) {
				sendOK(message);
			} else {
				logger.error("Error adding cache attribute in session", ar.cause());
				sendError(message, "Error adding cache attribute in session");
			}
		});
	}

	private void doRemoveAttribute(Message<JsonObject> message) {
		final String userId = message.body().getString("userId");
		if (userId == null || userId.trim().isEmpty()) {
			sendError(message, "[getSessionByUserId] Invalid userId : " + message.body().encode());
			return;
		}

		String key = message.body().getString("key");
		if (key == null || key.trim().isEmpty()) {
			sendError(message, "Invalid key.");
			return;
		}

		sessionStore.dropCacheAttributeByUserId(userId, key, ar -> {
			if (ar.succeeded()) {
				sendOK(message);
			} else {
				logger.error("Error dropping cache attribute in session", ar.cause());
				sendError(message, "Error dropping cache attribute in session");
			}
		});
	}

	private void generateSessionInfos(final String userId, final Handler<JsonObject> handler) {
		final String query =
				"MATCH (n:User {id : {id}}) " +
				"WHERE HAS(n.login) " +
				"OPTIONAL MATCH n-[:IN]->(gp:Group) " +
				"OPTIONAL MATCH gp-[:DEPENDS]->(s:Structure) " +
				"OPTIONAL MATCH gp-[:DEPENDS]->(c:Class) " +
				"OPTIONAL MATCH n-[rf:HAS_FUNCTION]->fg-[:CONTAINS_FUNCTION*0..1]->(f:Function) " +
				"OPTIONAL MATCH n<-[:RELATED]-(child:User) " +
				"RETURN distinct " +
				"n.classes as classNames, n.level as level, n.login as login, COLLECT(distinct [c.id, c.name]) as classes, " +
				"n.lastName as lastName, n.firstName as firstName, n.externalId as externalId, n.federated as federated, " +
				"n.birthDate as birthDate, n.changePw as forceChangePassword, n.needRevalidateTerms as needRevalidateTerms,HAS(n.deleteDate) as deletePending, " +
				"n.displayName as username, HEAD(n.profiles) as type, " +
				"COLLECT(distinct [child.id, child.lastName, child.firstName]) as childrenInfo, has(n.password) as hasPw, " +
				"COLLECT(distinct [s.id, s.name, s.hasApp]) as structures, COLLECT(distinct [f.externalId, rf.scope]) as functions, " +
				"COLLECT(distinct s.UAI) as uai, " +
				"COLLECT(distinct gp.id) as groupsIds, n.federatedIDP as federatedIDP, n.functions as aafFunctions, " +
				"REDUCE(acc=[], pRed IN COLLECT(COALESCE(s.optionEnabled, [])) | pRed+acc ) as optionEnabled";
		final String query2 =
				"MATCH (n:User {id : {id}})-[:IN]->()-[:AUTHORIZED]->(:Role)-[:AUTHORIZE]->(a:Action)" +
				"<-[:PROVIDE]-(app:Application) " +
				"WHERE HAS(n.login) " +
				"RETURN DISTINCT COLLECT(distinct [a.name,a.displayName,a.type]) as authorizedActions, " +
				"COLLECT(distinct [app.name,app.address,app.icon,app.target,app.displayName,app.display,app.prefix]) as apps";
		final String query3 =
				"MATCH (u:User {id: {id}})-[:IN]->(g:Group)-[auth:AUTHORIZED]->(w:Widget) " +
				"WHERE HAS(u.login) " +
				"AND ( NOT(w<-[:HAS_WIDGET]-(:Application)-[:PROVIDE]->(:WorkflowAction)) " +
				"XOR w<-[:HAS_WIDGET]-(:Application)-[:PROVIDE]->(:WorkflowAction)<-[:AUTHORIZE]-(:Role)<-[:AUTHORIZED]-g )  " +
				"OPTIONAL MATCH (w)<-[:HAS_WIDGET]-(app:Application) " +
				"WITH w, app, collect(auth) as authorizations " +
				"RETURN DISTINCT COLLECT({" +
					"id: w.id, name: w.name, " +
					"path: coalesce(app.address, '') + w.path, " +
					"js: coalesce(app.address, '') + w.js, "+
					"i18n: coalesce(app.address, '') + w.i18n, "+
					"application: app.name, " +
					"mandatory: ANY(a IN authorizations WHERE HAS(a.mandatory) AND a.mandatory = true)"+
				"}) as widgets";
		final String query4 = "MATCH (s:Structure) return s.id as id, s.externalId as externalId";
		final String query5 = "MATCH (u:User {id: {id}})-[:PREFERS]->(uac:UserAppConf) RETURN uac AS preferences";
		JsonObject params = new JsonObject();
		params.put("id", userId);
		JsonArray statements = new fr.wseduc.webutils.collections.JsonArray()
				.add(new JsonObject().put("statement", query).put("parameters", params))
				.add(new JsonObject().put("statement", query2).put("parameters", params))
				.add(new JsonObject().put("statement", query3).put("parameters", params))
				.add(new JsonObject().put("statement", query4))
				.add(new JsonObject().put("statement", query5).put("parameters", params));
		neo4j.executeTransaction(statements, null, true, false, true,
				new Handler<Message<JsonObject>>() {

			@Override
			public void handle(Message<JsonObject> message) {
				JsonArray results = message.body().getJsonArray("results");
				if ("ok".equals(message.body().getString("status")) && results != null && results.size() == 5 &&
						results.getJsonArray(0).size() > 0 && results.getJsonArray(1).size() > 0) {
					JsonObject j = results.getJsonArray(0).getJsonObject(0);
					JsonObject j2 = results.getJsonArray(1).getJsonObject(0);
					JsonObject j3 = results.getJsonArray(2).getJsonObject(0);
					JsonObject structureMapping = new JsonObject();
					for (Object o : results.getJsonArray(3)) {
						if (!(o instanceof JsonObject)) continue;
						JsonObject jsonObject = (JsonObject) o;
						structureMapping.put(jsonObject.getString("externalId"), jsonObject.getString("id"));
					}
					j.put("userId", userId);
					JsonObject functions = new JsonObject();
					JsonArray actions = new fr.wseduc.webutils.collections.JsonArray();
					JsonArray apps = new fr.wseduc.webutils.collections.JsonArray();
					for (Object o : getOrElse(j2.getJsonArray("authorizedActions"), new fr.wseduc.webutils.collections.JsonArray())) {
						if (!(o instanceof JsonArray)) continue;
						JsonArray a = (JsonArray) o;
						actions.add(new JsonObject()
								.put("name", a.getString(0))
								.put("displayName", a.getString(1))
								.put("type", a.getString(2)));
					}
					for (Object o : getOrElse(j2.getJsonArray("apps"), new fr.wseduc.webutils.collections.JsonArray())) {
						if (!(o instanceof JsonArray)) continue;
						JsonArray a = (JsonArray) o;
						apps.add(new JsonObject()
										.put("name", (String) a.getString(0))
										.put("address", (String) a.getString(1))
										.put("icon", (String) a.getString(2))
										.put("target", (String) a.getString(3))
										.put("displayName", (String) a.getString(4))
										.put("display", ((a.getValue(5) == null) || a.getBoolean(5)))
										.put("prefix", (String) a.getString(6))
						);
					}
					for (Object o : getOrElse(j.getJsonArray("aafFunctions"), new fr.wseduc.webutils.collections.JsonArray())) {
						if (o == null) continue;
						String [] sf = o.toString().split("\\$");
						if (sf.length == 5) {
							JsonObject jo = functions.getJsonObject(sf[1]);
							if (jo == null) {
								jo = new JsonObject().put("code", sf[1])
										.put("functionName", sf[2])
										.put("scope", new fr.wseduc.webutils.collections.JsonArray())
										.put("structureExternalIds", new fr.wseduc.webutils.collections.JsonArray())
										.put("subjects", new JsonObject());
								functions.put(sf[1], jo);
							}
							JsonObject subject = jo.getJsonObject("subjects").getJsonObject(sf[3]);
							if (subject == null) {
								subject = new JsonObject()
										.put("subjectCode", sf[3])
										.put("subjectName", sf[4])
										.put("scope", new fr.wseduc.webutils.collections.JsonArray())
										.put("structureExternalIds", new fr.wseduc.webutils.collections.JsonArray());
								jo.getJsonObject("subjects").put(sf[3], subject);
							}
							jo.getJsonArray("structureExternalIds").add(sf[0]);
							subject.getJsonArray("structureExternalIds").add(sf[0]);
							String sid = structureMapping.getString(sf[0]);
							if (sid != null) {
								jo.getJsonArray("scope").add(sid);
								subject.getJsonArray("scope").add(sid);
							}
						}
					}
					j.remove("aafFunctions");
					for (Object o : getOrElse(j.getJsonArray("functions"), new fr.wseduc.webutils.collections.JsonArray())) {
						if (!(o instanceof JsonArray)) continue;
						JsonArray a = (JsonArray) o;
						String code = a.getString(0);
						if (code != null) {
							functions.put(code, new JsonObject()
									.put("code", code)
									.put("scope", a.getJsonArray(1))
							);
						}
					}
					final JsonObject children = new JsonObject();
					final List<String> childrenIds = new ArrayList<String>();
					for (Object o : getOrElse(j.getJsonArray("childrenInfo"), new fr.wseduc.webutils.collections.JsonArray())) {
						if (!(o instanceof JsonArray)) continue;
						final JsonArray a = (JsonArray) o;
						final String childId = a.getString(0);
						if (childId != null) {
							childrenIds.add(childId);
							JsonObject jo = children.getJsonObject(childId);
							if (jo == null) {
								jo = new JsonObject()
										.put("lastName", a.getString(1))
										.put("firstName", a.getString(2));
								children.put(childId, jo);
							}
						}
					}
					j.remove("childrenInfo");
					final List<String> classesIds = new ArrayList<String>();
					final List<String> classesNames = new ArrayList<String>() ;
					for (Object o : getOrElse(j.getJsonArray("classes"), new fr.wseduc.webutils.collections.JsonArray())) {
						if (!(o instanceof JsonArray)) continue;
						final JsonArray c = (JsonArray) o;
						if (c.getString(0) != null) {
							classesIds.add(c.getString(0));
							classesNames.add(c.getString(1));
						}
					}
					j.remove("classes");
					final List<String> structureIds = new ArrayList<String>();
					final List<String> structureNames = new ArrayList<String>() ;
					boolean hasApp = false;
					for (Object o : getOrElse(j.getJsonArray("structures"), new fr.wseduc.webutils.collections.JsonArray())) {
						if (!(o instanceof JsonArray)) continue;
						final JsonArray s = (JsonArray) o;
						if (s.getString(0) != null) {
							structureIds.add(s.getString(0));
							structureNames.add(StringUtils.trimToBlank(s.getString(1)));
							if(getOrElse(s.getBoolean(2), false) && !hasApp)
								hasApp = true;
						}
					}
					j.remove("structures");
					j.put("structures", new fr.wseduc.webutils.collections.JsonArray(structureIds));
					j.put("structureNames", new fr.wseduc.webutils.collections.JsonArray(structureNames));
					j.put("hasApp", hasApp);
					j.put("classes", new fr.wseduc.webutils.collections.JsonArray(classesIds));
					j.put("realClassesNames", new fr.wseduc.webutils.collections.JsonArray(classesNames));
					j.put("functions", functions);
					j.put("authorizedActions", actions);
					j.put("apps", apps);
					j.put("childrenIds", new fr.wseduc.webutils.collections.JsonArray(childrenIds));
					j.put("children", children);
					final JsonObject cache = (results.getJsonArray(4) != null && results.getJsonArray(4).size() > 0 &&
							results.getJsonArray(4).getJsonObject(0) != null) ? results.getJsonArray(4).getJsonObject(0) : new JsonObject();
					j.put("cache", cache);
					j.put("widgets", getOrElse(j3.getJsonArray("widgets"), new fr.wseduc.webutils.collections.JsonArray()));
					//return unique options
					Set<String> uniquOption = new HashSet<>(j.getJsonArray("optionEnabled", new JsonArray()).getList());
					j.put("optionEnabled", new JsonArray(new ArrayList(uniquOption)));
					//
					handler.handle(j);
				} else {
					handler.handle(null);
				}
			}
		});
	}

}
