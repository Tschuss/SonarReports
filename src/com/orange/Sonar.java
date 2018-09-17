package com.orange;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class Sonar {
	static final String APP_COMPONENTS_JSON="appComponents.json";
	static final String APP_COMPONENTS_TS="src/app/components/app-components.ts";
	static final String PACKAGE_CONFIG_JSON="package.config.json"; //PARA ANGULAR5

	static final String MASTER_BRANCH="master";
	static final String RELEASE_BRANCH="release";
	static final String DEVELOP_BRANCH="develop";

	static final String SONAR_KEY = "key";
	static final String SONAR_ID = "id";
	static final String SONAR_STATUS = "status";
	
	static final String SONAR_BLOCKING = "blocker_violations";
	static final String SONAR_CRITICAL = "critical_violations";
	static final String SONAR_DUPLICATION = "duplicated_lines_density";
	static final String SONAR_DEBT_RATIO = "sqale_debt_ratio";
	static final String SONAR_VULNERABILITY = "vulnerabilities";
	static final String SONAR_COVERAGE = "coverage";
	
	
	static HashMap<String, String> projects= new HashMap<>();
	
	static Set<String> excluded = new HashSet<>();

	public static void main(String[] args) {
		
		String text="COMPONENTE;REPO;VERSION;GATE;BLOCKING;CRITICAL;DUPLICATION;COMPLEXITY";
	
		excluded.add("ARCH_ANGULAR_COMPONENT_BASE"); //POC de arquitectura
		
		try {
			Map<String, String> projectsMap=getGitLab("projects?search=_ANG", true);
			String projectsStr=projectsMap.get("gitlab");
			while (projectsMap.get("next")!=null) {
				projectsMap = getGitLab(projectsMap.get("next"), true);
				projectsStr+=projectsMap.get("gitlab");
				//arreglamos el JSON generado
				projectsStr=projectsStr.substring(0, projectsStr.indexOf("]["))+","+projectsStr.substring(projectsStr.indexOf("][")+2);
			}
			//System.out.println(projectsStr);

			//hay que completar el Json para que el parser lo entienda...
			JSONObject json = new JSONObject("{'projects':"+projectsStr+"}");
			JSONArray projectsArray = json.getJSONArray("projects");
			System.out.println("#repos="+projectsArray.length());
			for (int i=0; i<projectsArray.length();i++) {
				JSONObject project= (JSONObject)projectsArray.get(i);
				String name=project.getString("name");
				if (!excluded.contains(name)) {
					//System.out.println(name);
					int id=project.getInt("id");
					projects.put(String.valueOf(id),name);
					text+=getComponentInfo (name, id);		
				} else {
					System.err.println("["+name+"] se excluye del analisis...");
				}
			}

		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		
		System.out.println(text);
		
	}


	static String getComponentInfo (String project, int id) {
		String csv="\n";
		try {
			Map<String, String> gitlab = parseComponentName(project, id);
			csv+=gitlab.get("name");
			csv+=";"+project;
			csv+=";"+gitlab.get("version");
	
			Map<String, String> sonar= getSonarResults(project);
			csv+=";"+sonar.get("status");
			csv+=";"+sonar.get("blocking");
			csv+=";"+sonar.get("critical");
			csv+=";"+sonar.get("duplication");
			csv+=";"+sonar.get("debt");
			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			System.err.println(e.getMessage());
		}
		return csv;
	}

	static Map<String, String> parseComponentName(String project, int id) {
		
		String content="";
		String branch=MASTER_BRANCH;
		String file="package.json";
		HashMap<String, String> map= new HashMap<>();
		try {		
			try {
				//intentamos coger la version de produccion la primera
				content = getGitLabFile(id, file, branch,false);
			} catch (FileNotFoundException e) {
				System.err.println(e.getMessage());
				try {
					//si no, cogemos la de UAT
					branch=RELEASE_BRANCH;
					content = getGitLabFile(id, file, branch,false);
				} catch (FileNotFoundException ee) {
					System.err.println(e.getMessage());
					try {
						//por ultimo cogemos la de desarrollo
						branch=DEVELOP_BRANCH;
						content = getGitLabFile(id, file, branch,false);
					} catch (FileNotFoundException eee) {
						System.err.println(eee.getMessage());
						return map;
					}
				}
			}
			//System.out.println(content);
			try {
				JSONObject json = new JSONObject(content);
				map.put("name", json.getString("name"));
			} catch (JSONException je) {
				System.err.println(je.getMessage());
			}

			//System.out.println(csv);
		} catch (IOException eee) {
			System.err.println(eee.getMessage());
		}
		return map;	

	}
	
	static String parseInnerComponents(String project, int id, String file) {
		
		String csv="";
		String content="";
		String branch=MASTER_BRANCH;
		
		try {		
			try {

				content = getGitLabFile(id, file, branch,false);
			} catch (FileNotFoundException e) {
				System.err.println(e.getMessage());
				try {
					branch=DEVELOP_BRANCH;
					content = getGitLabFile(id, file, branch, false);
				} catch (FileNotFoundException ee) {
					System.err.println(ee.getMessage());
					content="";
				}
			}

			//parserar el typescript
			int a=content.indexOf("app-components");
			if (a>0) {
				int b = content.indexOf("'", a+15); //comilla antes del componente
				while (b>0) {
					int c = content.indexOf("'", b+1); //comilla despues del componente
					String name = content.substring(b+1, c);
					//System.out.println(name);
					csv+="\n"+project+";"+name+";-1;"+branch+";NO";
					b=content.indexOf("'", c+1);
				} 
			}

		} catch (IOException eee) {
			System.err.println(eee.getMessage());
		}
		return csv;	

	}

	static String getGitLabFile (int id, String file, String branch, boolean v4) throws IOException {
		String urlFile=URLEncoder.encode(file, "UTF-8");
		
		urlFile=urlFile.replaceAll("\\.", "%2E").replaceAll("-", "%2D").replace("_","%5F");
		String url="";
		if (v4) {
			url = "https://torredecontrol.si.orange.es/gitlab/api/v4/projects/"+id+"/repository/files/"+urlFile+"/raw?ref="+branch;
		} else {//v3
			url = "https://torredecontrol.si.orange.es/gitlab/api/v3/projects/"+id+"/repository/files?file_path="+file+"&ref="+branch;			
		}
		URL obj = new URL(url);
		HttpURLConnection con = (HttpURLConnection) obj.openConnection();

		// optional default is GET
		con.setRequestMethod("GET");

		//add request header
		con.setRequestProperty("PRIVATE-TOKEN", "nMW2c2Tjsxp5m-zqnZaG");

		int responseCode = con.getResponseCode();
		//System.out.println("\nSending 'GET' request to URL : " + url);
		if (responseCode != 200) {
			throw new FileNotFoundException("Archivo ["+file+"] no encontrado en la rama ["+branch+"] del proyecto ["+projects.get(String.valueOf(id))+"]: http ["+url+"] error code: "+responseCode);
		} else {
			BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
			String inputLine;
			StringBuffer response = new StringBuffer();

			while ((inputLine = in.readLine()) != null) {
				response.append(inputLine);
			}
			in.close();
			//print result
			//System.out.println(response.toString());
			if (v4) {
				return response.toString();
			} else {//v3
				JSONObject json = new JSONObject(response.toString());
				String content=json.getString("content");
				byte bytes[]=content.getBytes();
				byte[] valueDecoded = Base64.getDecoder().decode(bytes);
				//System.out.println("Decoded value is " + new String(valueDecoded));	
				return new String(valueDecoded);
			}
		}
	}

	static Map<String,String> getGitLab (String uri, boolean v4) throws IOException {
		String url="";
		String gitlab="";
		String next="";
		Map <String, String> answer=new HashMap<>();
		if (v4) {
			url = "https://torredecontrol.si.orange.es/gitlab/api/v4/"+uri;
		} else {//v3
			url = "https://torredecontrol.si.orange.es/gitlab/api/v3/"+uri;			
		}
		URL obj = new URL(url);
		HttpURLConnection con = (HttpURLConnection) obj.openConnection();

		// optional default is GET
		con.setRequestMethod("GET");

		//add request header
		con.setRequestProperty("PRIVATE-TOKEN", "nMW2c2Tjsxp5m-zqnZaG");

		int responseCode = con.getResponseCode();
		if (responseCode != 200) {
			throw new FileNotFoundException("Recurso ["+uri+"] no encontrado: http ["+url+"] error code: "+responseCode);
		} else {
			BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
			String inputLine;
			StringBuffer response = new StringBuffer();

			while ((inputLine = in.readLine()) != null) {
				response.append(inputLine);
			}
			in.close();
			gitlab+=response.toString();
			//print gitlab
			//System.out.println("JSON => "+gitlab);
			answer.put("gitlab", gitlab);
			
			//miramos si está paginado y hay más paginas
			Map<String, List<String>> headers = con.getHeaderFields();
			//System.out.println("\nSending 'GET' request to URL : " + url + "headers: "+headers);
			List<String> links = headers.get("Link");
			//System.out.println(links);
			String[] nexts=links.get(0).split(",|;");
			for (int i=0;i<nexts.length;i++) {
				if (nexts[i].indexOf("next")>0) {
					next=nexts[i-1].substring(1, nexts[i-1].length()-1);		
					next= next.substring(next.indexOf("projects?"));
					//System.err.println("Next="+next);
					answer.put("next", next);
					break;
				} else {
					//System.err.println("No hay Next");
				}
		    }
			//System.out.println("Headers => "+answer.toString());
			return answer;
			
		}
	}

	public static Map<String, String> getSonarResults(String project) throws IOException {
		/*
		 * Busca un proyecto en Sonar: http://10.113.64.123:9000/sonar/api/components/show?component=CAJALOGADOUNIFICADO_ANG_SPA
		 * Obtiene los gates a partir del KEY: http://10.113.64.123:9000/sonar/api/qualitygates/project_status?projectKey=CAJALOGADOUNIFICADO_ANG_SPA
		 */
		Map<String, String> result = new HashMap<String, String>();
		
		String sonar="";
		String url="http://10.113.64.123:9000/sonar/api/components/show?component="+project;
		URL obj = new URL(url);
		HttpURLConnection con = (HttpURLConnection) obj.openConnection();

		// optional default is GET
		con.setRequestMethod("GET");

		//add request header
		//con.setRequestProperty("f738b029171b78b2275ba29f56afdf3c29eda37c:", "");

		int responseCode = con.getResponseCode();
		if (responseCode != 200) {
			throw new FileNotFoundException("Recurso ["+project+"] no encontrado: http ["+url+"] error code: "+responseCode);
		} else {
			BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
			String inputLine;
			StringBuffer response = new StringBuffer();

			while ((inputLine = in.readLine()) != null) {
				response.append(inputLine);
			}
			in.close();
			sonar+=response.toString();
		}
		//System.out.println(sonar);

		JSONObject json = new JSONObject(sonar);
		JSONObject component= json.getJSONObject("component");
		result.put(SONAR_KEY, component.getString(SONAR_KEY));
		result.put(SONAR_ID, component.getString(SONAR_ID));

		//ahora pedimos los resultados del ultimo analisis
		url="http://10.113.64.123:9000/sonar/api/qualitygates/project_status?projectKey="+result.get(SONAR_KEY);
		obj= new URL(url);
		con = (HttpURLConnection) obj.openConnection();
		String gates="";

		// optional default is GET
		con.setRequestMethod("GET");

		//add request header
		//con.setRequestProperty("f738b029171b78b2275ba29f56afdf3c29eda37c:", "");

		responseCode = con.getResponseCode();
		if (responseCode != 200) {
			throw new FileNotFoundException("Recurso ["+project+"] no encontrado: http ["+url+"] error code: "+responseCode);
		} else {
			BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
			String inputLine;
			StringBuffer response = new StringBuffer();

			while ((inputLine = in.readLine()) != null) {
				response.append(inputLine);
			}
			in.close();
			gates+=response.toString();
		}
		//System.out.println(gates);
		
		json = new JSONObject(gates);
		JSONObject projectStatus= json.getJSONObject("projectStatus");
		result.put("status", projectStatus.getString("status"));
		
		JSONArray conditions = projectStatus.getJSONArray("conditions");
		for (int i=0;i<conditions.length();i++) {
			switch (conditions.getJSONObject(i).getString("metricKey")) {
			case SONAR_BLOCKING: 			
				result.put("blocking", conditions.getJSONObject(i).getString("actualValue"));
				break;
			case SONAR_CRITICAL:
				result.put("blocking", conditions.getJSONObject(i).getString("actualValue"));
				break;
			case SONAR_DUPLICATION:
				result.put("duplication", conditions.getJSONObject(i).getString("actualValue"));
				break;
			case SONAR_COVERAGE:
				result.put("coverage", conditions.getJSONObject(i).getString("actualValue"));
				break;
			case SONAR_VULNERABILITY:
				result.put("vulnerability", conditions.getJSONObject(i).getString("actualValue"));
				break;
			case SONAR_DEBT_RATIO:
				result.put("debt", conditions.getJSONObject(i).getString("actualValue"));
				break;

			}	
		}
		return result;
	}

}
