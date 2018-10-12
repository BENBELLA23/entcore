/*
 * Copyright © "Open Digital Education", 2016
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

 */

package org.entcore.cas.services;

import fr.wseduc.cas.entities.User;
import fr.wseduc.webutils.Either;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.entcore.common.neo4j.Neo4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.entcore.common.neo4j.Neo4jResult.*;

public class EnglishAttackRegisteredService extends AbstractCas20ExtensionRegisteredService {

	private static final Logger log = LoggerFactory.getLogger(EnglishAttackRegisteredService.class);

	protected static final String EA_ID = "uid";
	protected static final String EA_STRUCTURE = "ENTPersonStructRattach";
	protected static final String EA_STRUCTURE_UAI = "ENTPersonStructRattachRNE";
	protected static final String EA_STRUCTURE_NAME = "ENTPersonStructRattachName";
	protected static final String EA_PROFILES = "ENTPersonProfils";
	protected static final String EA_CLASSE = "ENTClasse";
	protected static final String EA_DISCIPLINE = "ENTDiscipline";
	protected static final String EA_EMAIL = "ENTPersonMail";
	protected static final String EA_LASTNAME = "Nom";


	protected static final String EA_FIRSTNAME = "Prenoms";
	private final Pattern classGroupPattern = Pattern.compile(".*\\$(.*)");
	private final Neo4j neo = Neo4j.getInstance();

	@Override
	public void configure(EventBus eb, Map<String,Object> conf) {
		super.configure(eb, conf);
		this.directoryAction = "getUserInfos";
	};

	@Override
	protected void prepareUserCas20(User user,final String userId, String service,final JsonObject data, final Document doc,final List<Element> additionnalAttributes) {
		user.setUser(data.getString(principalAttributeName));

		if (log.isDebugEnabled()){
			log.debug("START : prepareUserCas20 userId : " + userId);
		}

		try {
			if (log.isDebugEnabled()){
				log.debug("DATA : prepareUserCas20 data : " + data);
			}

			String query = "MATCH (u:`User` { id : {id}}) return u";
			JsonObject params = new JsonObject().put("id", userId);
			neo.execute(query, params, new Handler<Message<JsonObject>>() {
				@Override
				public void handle(Message<JsonObject> m) {

					// Uid
					if (data.containsKey("externalId")) {
						additionnalAttributes.add(createTextElement(EA_ID, data.getString("externalId"), doc));
					}

					// Structures
					Element rootStructures = createElement(EA_STRUCTURE+"s", doc);
					for (Object o : data.getJsonArray("structures", new fr.wseduc.webutils.collections.JsonArray()).getList()) {
						if (o == null || !(o instanceof JsonObject)) continue;
						JsonObject structure = (JsonObject) o;
						Element rootStructure = createElement(EA_STRUCTURE, doc);

						if (structure.containsKey("UAI")) {
							rootStructure.appendChild(createTextElement(EA_STRUCTURE_UAI, structure.getString("UAI"), doc));
						}
						if (structure.containsKey("name")) {
							rootStructure.appendChild(createTextElement(EA_STRUCTURE_NAME, structure.getString("name"), doc));
						}
						rootStructures.appendChild(rootStructure);
					}
					additionnalAttributes.add(rootStructures);

					// Profile
					switch(data.getString("type")) {
						case "Student" :
							additionnalAttributes.add(createTextElement(EA_PROFILES, "National_1", doc));
							addStringArray(EA_CLASSE, "classes","name", data, doc, additionnalAttributes, new Mapper<String, String>(){
								String map(String input) {
									Matcher m = classGroupPattern.matcher(input);
									if(m.matches() && m.groupCount() >= 1){
										return m.group(1);
									}
									return input;
								}
							});
							// Email
							if (data.containsKey("email")) {
								additionnalAttributes.add(createTextElement(EA_EMAIL, data.getString("email"), doc));
							}
							break;
						case "Teacher" :
							additionnalAttributes.add(createTextElement(EA_PROFILES, "National_3", doc));
							addStringArray(EA_CLASSE, "classes","name", data, doc, additionnalAttributes, new Mapper<String, String>(){
								String map(String input) {
									Matcher m = classGroupPattern.matcher(input);
									if(m.matches() && m.groupCount() >= 1){
										return m.group(1);
									}
									return input;
								}
							});

							//Discipline
							Either<String, JsonObject> res = validUniqueResult(m);
							if (res.isRight()) {
								JsonObject j = res.right().getValue();
								if(null != j.getJsonObject("u")
										&& null != j.getJsonObject("u").getJsonObject("data")
										&& null != j.getJsonObject("u").getJsonObject("data").getJsonArray("functions")){
									JsonArray jsonArrayFunctions = j.getJsonObject("u").getJsonObject("data").getJsonArray("functions");
									if(jsonArrayFunctions.size() > 0){
										Element rootDisciplines = createElement(EA_DISCIPLINE+"s", doc);
										List<String> vTempListDiscipline = new ArrayList<>();
										for (int i = 0; i < jsonArrayFunctions.size(); i++) {
											String fonction = jsonArrayFunctions.getString(i);
											String[] elements = fonction.split("\\$");
											if ( elements.length > 1 ){
												String discipline = elements[elements.length - 2] + "$" + elements[elements.length - 1];
												if(!vTempListDiscipline.contains(discipline)){
													vTempListDiscipline.add(discipline);
													rootDisciplines.appendChild(createTextElement(EA_DISCIPLINE, discipline, doc));
												}
											}else{
												log.error("Failed to get User functions userID, : " + userId + " fonction : " + fonction);
											}
										}
										additionnalAttributes.add(rootDisciplines);
									}
								}else{
									log.error("Failed to get User functions userID, user empty : " + userId + " j : " + j);
								}
							} else {
								log.error("Failed to get User functions userID : " + userId);
							}
							// Email
							if (data.containsKey("emailAcademy")) {
								additionnalAttributes.add(createTextElement(EA_EMAIL, data.getString("emailAcademy"), doc));
							} else if (data.containsKey("email")) {
								additionnalAttributes.add(createTextElement(EA_EMAIL, data.getString("email"), doc));

							}
							break;
						case "Relative" :
							additionnalAttributes.add(createTextElement(EA_PROFILES, "National_2", doc));
							break;
						case "Personnel" :
							additionnalAttributes.add(createTextElement(EA_PROFILES, "National_4", doc));
							break;
					}

					// Lastname
					if (data.containsKey("lastName")) {
						additionnalAttributes.add(createTextElement(EA_LASTNAME, data.getString("lastName"), doc));
					}

					// Firstname
					if (data.containsKey("firstName")) {
						additionnalAttributes.add(createTextElement(EA_FIRSTNAME, data.getString("firstName"), doc));
					}
				}
			});

		} catch (Exception e) {
			log.error("Failed to transform User for EnglishAttack", e);
		}

		if (log.isDebugEnabled()){
			log.debug("START : prepareUserCas20 userId : " + userId);
		}
	}

	private void addStringArray(String casLabel, String entLabel,String entLabel2, JsonObject data, Document doc, List<Element> additionalAttributes, Mapper<String, String> mapper){
		Element root = createElement(casLabel+"s", doc);
		if(data.containsKey(entLabel)){
			for(Object item: data.getJsonArray(entLabel)){
				if (item.getClass().getName().equalsIgnoreCase(JsonObject.class.getName())) {
					root.appendChild(createTextElement(casLabel, ((JsonObject) item).getString(entLabel2), doc));
				} else {
					root.appendChild(createTextElement(casLabel, mapper.map((String) item), doc));
				}
			}
		}
		additionalAttributes.add(root);
	}


	private abstract class Mapper<IN, OUT>{
		abstract OUT map(IN input);
	}
	private class DefaultMapper<A> extends Mapper<A, A> {
		A map(A input){
			return input;
		}
	}
}
