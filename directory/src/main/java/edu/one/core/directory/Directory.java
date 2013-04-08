package edu.one.core.directory;

import edu.one.core.infra.Controller;
import edu.one.core.infra.Neo;
import java.util.Map;
import org.vertx.java.core.Handler;
import org.vertx.java.core.http.HttpServerRequest;
import org.vertx.java.core.json.JsonObject;

public class Directory extends Controller {
	
	JsonObject dataMock;
	Neo neo;

	@Override
	public void start() throws Exception {
		super.start();
		neo = new Neo(vertx.eventBus(),log);
		dataMock = new JsonObject(vertx.fileSystem().readFileSync("directory-data-mock.json").toString());
		container.deployModule("edu.one~wordpress~1.0.0-SNAPSHOT");
		
		rm.get("/admin", new Handler<HttpServerRequest>() {
			@Override
			public void handle(HttpServerRequest request) {
				renderView(request, new JsonObject());
			}
		});
		
		rm.get("/api/ecole", new Handler<HttpServerRequest>() {
			@Override
			public void handle(HttpServerRequest request) {
				neo.send("START n=node(*) WHERE has(n.type) "
						+ "AND n.type='ETABEDUCNAT' "
						+ "RETURN distinct n.ENTStructureNomCourant, n.id", request.response);
			}
		});
		
		rm.get("/api/classes", new Handler<HttpServerRequest>() {
			@Override
			public void handle(HttpServerRequest request) {
				String schoolId = request.params().get("id");
				neo.send("START n=node(*) MATCH n<--m WHERE has(n.id) "
						+ "AND n.id='" + schoolId + "' "
						+ "AND has(m.type) AND m.type='CLASSE' "
						+ "RETURN distinct m.ENTGroupeNom, m.id, n.id", request.response);
			}
		});
		
		rm.get("/api/personnes", new Handler<HttpServerRequest>() {
			@Override
			public void handle(HttpServerRequest request) {
				String classId = request.params().get("id").replaceAll("-", " ").replaceAll("_", "\\$");
				neo.send("START n=node(*) MATCH n<--m WHERE has(n.id) "
						+ "AND n.id='" + classId + "' "
						+ "AND has(m.type) and m.type='ELEVE' "
						+ "RETURN distinct m.id,m.ENTPersonNom,m.ENTPersonPrenom, n.id", request.response);
			}
		});

		rm.get("/api/details", new Handler<HttpServerRequest>() {
			@Override
			public void handle(HttpServerRequest request) {
				String personId = request.params().get("id");
				neo.send("START n=node(*) WHERE has(n.id) "
						+ "AND n.id='" + personId + "' "
						+ "RETURN distinct n.ENTPersonNom, n.ENTPersonPrenom, n.ENTPersonAdresse", request.response);
			}
		});

		rm.get("/api/export", new Handler<HttpServerRequest>() {
			@Override
			public void handle(HttpServerRequest request) {
				String neoRequest = createExportRequest(request.params());
				neo.send(neoRequest, request.response);
			}
		});

	}


	private String createExportRequest(Map<String,String> params){
		if (params.isEmpty()){
			return "START m=node(*) WHERE has(m.type) "
					+ "AND (m.type='ELEVE' OR m.type='PERSEDUCNAT') "
					+ "RETURN distinct m.id,m.ENTPersonNom, m.ENTPersonPrenom";
		} else {
			return "START n=node(*) MATCH n<--m "
					+ "WHERE has(n.id) AND n.id='" + params.get("id") + "' "
					+ "AND has(m.type) AND (m.type='ELEVE' OR m.type='PERSEDUCNAT') "
					+ "RETURN distinct m.id,m.ENTPersonNom,m.ENTPersonPrenom";
		}
	}

}