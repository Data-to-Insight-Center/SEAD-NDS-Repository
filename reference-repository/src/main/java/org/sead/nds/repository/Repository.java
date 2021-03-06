/*
 * Copyright 2015, 2016 University of Michigan
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
 *
 * @author myersjd@umich.edu
 * @author isuriara@indiana.edu
 */

package org.sead.nds.repository;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.sead.nds.repository.util.DataCiteMetadataTemplate;
import org.sead.nds.repository.util.DataCiteRESTfulClient;
import org.sead.nds.repository.util.SEADException;

import java.io.IOException;
import java.util.*;

public class Repository {

	static final Logger log = LogManager.getLogger(Repository.class);
	private static String repoID = null;
	private static Properties props;
	private static String dataPath = null;
	private static boolean allowUpdates = false;

	static int numThreads;

	public Repository() {
	}

	// Important: SDA Agent also uses this Repository class and passes it's own
	// properties.
	// Therefore, don't load properties inside the init method.
	public static void init(Properties properties) {
		props = properties;
		repoID = props.getProperty("repo.ID");
		dataPath = props.getProperty("repo.datapath");

		if ((repoID == null) || (dataPath == null)) {
			log.error("Unable to find repoId and dataPath in proporties file");
		}
		allowUpdates = (props.getProperty("repo.allowupdates", "false"))
				.equalsIgnoreCase("true") ? true : false;
		numThreads = Runtime.getRuntime().availableProcessors();
		if (props.getProperty("repo.numthreads") != null) {
			numThreads = Integer.parseInt(props.getProperty("repo.numthreads"));
		}
		log.debug("Using " + numThreads + " threads");
	}

	public static int getNumThreads() {
		return numThreads;
	}

	public static Properties getProps() {
		return props;
	}

	public static boolean getAllowUpdates() {
		return allowUpdates;
	}

	public static String getRepoID() {
		return repoID;
	}

	static public String getDataPath() {
		return dataPath;
	}

	public static Properties loadProperties() {
		Properties props = new Properties();
		try {
			props.load(Repository.class
					.getResourceAsStream("/repository.properties"));
			log.trace(props.toString());
		} catch (IOException e) {
			log.warn("Could not read repositories.properties file");
		}
		return props;
	}

	static String getLandingPageUri(String bagName) {
		return props.getProperty("repo.landing.base") + bagName;
	}

	public static String createDOIForRO(String bagID, C3PRPubRequestFacade RO) throws SEADException, IOException {
		String target = Repository.getLandingPageUri(bagID);
		log.debug("DOI Landing Page: " + target);
		String existingID = null;
		if (RO.getPublicationRequest().getJSONObject(PubRequestFacade.PREFERENCES)
				.has(PubRequestFacade.EXTERNAL_IDENTIFIER)) {
			existingID = RO.getPublicationRequest()
					.getJSONObject(PubRequestFacade.PREFERENCES)
					.getString(PubRequestFacade.EXTERNAL_IDENTIFIER);
			if (existingID.startsWith("http://dx.doi.org/")) {
				existingID = existingID.substring("http://dx.doi.org/".length());
			}
			// Moving to new resolver - check for both
			if (existingID.startsWith("http://doi.org/")) {
				existingID = existingID.substring("http://doi.org/".length());
			}
			if (existingID != null && !allowUpdates) {
				// FixMe - should we fail instead of going forward with a new
				// ID?
				log.warn("User requested an update to an existing ID, which is not allowed: Ingoring update request.");
			}
		}



		HashMap<String, String> metadata = new LinkedHashMap<String, String>();
        
		metadata.put(DataCiteMetadataTemplate.title, ((JSONObject) RO
                .getOREMap().get("describes")).getString("Title"));
		String rightsholderString = "SEAD (http://sead-data.net)";
	      if (RO.getPublicationRequest().has("Rights Holder")) {
	            rightsholderString = RO.getPublicationRequest().getString(
	                    "Rights Holder")
	                    + ", " + rightsholderString;
	        } else {
	            log.warn("Request has no Rights Holder");
	        }
		metadata.put(DataCiteMetadataTemplate.publisher,rightsholderString);
		metadata.put(DataCiteMetadataTemplate.publicationYear,String.valueOf(Calendar.getInstance().get(Calendar.YEAR)));
        metadata.put(DataCiteMetadataTemplate.description, ((JSONObject) RO
                .getOREMap().get("describes")).getString("Abstract"));
		
        // ToDo - Add Contributors/Contacts
        metadata.put(DataCiteMetadataTemplate.creators, DataCiteMetadataTemplate.generateCreatorsXml((JSONArray) RO.getOREMap()
                                .getJSONObject("describes").get("Creator")));
        JSONArray contacts = (JSONArray) (RO.getOREMap()
                .getJSONObject("describes").has("Contact") ? 
                        RO.getOREMap().getJSONObject("describes").get("Contact") :
                            RO.getOREMap().getJSONObject("describes").get("Creator"));
                        
        metadata.put(DataCiteMetadataTemplate.contributors, DataCiteMetadataTemplate.generateContributorsXml(DataCiteMetadataTemplate.contactType, contacts));

        /* Add various types of related objects (from
         * https://schema.datacite.org/meta/kernel-4.1/include/datacite-relationType-v4.1.xsd)
         * that are specified by urn, url or DOI (from
         * https://schema.datacite.org/meta/kernel-4.1/include/datacite-relatedIdentifierType-v4.xsd)
         */ 
        JSONObject describes = RO.getOREMap()
                .getJSONObject("describes");
        JSONObject relatedIds = new JSONObject();
        //FixMe - use context
        //hasPart
        if (describes.has("Has Part")) {
            relatedIds.put("HasPart", getValues(describes.get("Has Part")));
        }
        //references (or References)
        if (describes.has("references")) {
            relatedIds.put("References", getValues(describes.get("references")));
        }
        if (describes.has("References")) {
            relatedIds.put("References", getValues(describes.get("References")));
        }
        //Primary/Initial Publication
        if (describes.has("Primary/Initial Publication")) {
            relatedIds.put("IsReferencedBy", getValues(describes.get("Primary/Initial Publication")));
        }
        //Published In
        if (describes.has("Published In")) {
            relatedIds.put("IsPartOf", getValues(describes.get("Published In")));
        }
        //Is Version Of
        if (describes.has("Is Version Of")) {
            relatedIds.put("IsVersionOf", getValues(describes.get("Is Version Of")));
        }
        //Is Referenced By
        if (describes.has("Is Referenced By")) {
            relatedIds.put("IsReferencedBy", getValues(describes.get("Is Referenced By")));
        }
 		metadata.put(DataCiteMetadataTemplate.relatedIdentifiers,DataCiteMetadataTemplate.generateRelatedIdentifiers(relatedIds));
		
		// Get allowed purpose(s) from profile
		JSONObject repository = RO.getRepositoryProfile();
		String[] allowedPurposes = {PubRequestFacade.PRODUCTION };
		if (repository.has(PubRequestFacade.PURPOSE)) {
			Object repoPurpose = repository.get(PubRequestFacade.PURPOSE);
			allowedPurposes = RO.normalizeValues(repoPurpose);
		}

		// Get requested purpose
		boolean production = true;
		// (Backward-compatible if repository config has a default when no
		// Purpose preference is sent
		if (props.get("doi.default") != null) {
			production = props.get("doi.default").equals("temporary") ? false
					: true;
		}
		String purposePref = PubRequestFacade.PRODUCTION;
		if (((JSONObject) RO.getPublicationRequest().get(PubRequestFacade.PREFERENCES))
				.has(PubRequestFacade.PURPOSE)) {
			purposePref = ((JSONObject) RO.getPublicationRequest().get(
					PubRequestFacade.PREFERENCES)).getString(PubRequestFacade.PURPOSE);
			if (purposePref.equalsIgnoreCase(PubRequestFacade.TESTING)) {
				production = false;
			} else if (!purposePref.equalsIgnoreCase(PubRequestFacade.PRODUCTION)) {
				// Should be the only option today, but warn if it doesn't match
				log.warn("Unknown Purpose Preference: " + purposePref);
			} else {
			    production=true; //Used to decide which shoulder to use
			}
		}
		boolean purposeIsAllowed = false;
		for (String purp : allowedPurposes) {
			if (purp.equals(purposePref)) {
				purposeIsAllowed = true;
			}
		}

		if (!purposeIsAllowed) {
			throw new SEADException("Repository not allowed to mint "
					+ purposePref + " identifier");
		}
		DataCiteRESTfulClient client = new DataCiteRESTfulClient(props.getProperty("datacite.url"), props.getProperty("doi.user"), props.getProperty("doi.pwd"));
		
		String shoulder = (production) ? props.getProperty("doi.shoulder.prod")
				: props.getProperty("doi.shoulder.test");
		String doi = null;

		if ((existingID != null) && (existingID.contains(shoulder))
				&& allowUpdates) {

			// Enhancement: Retrieve metadata first and find current landing
			// page for this DOI - can then
			// decide what to do, e.g. to move/remove the old version, do
			// something other than a 404 for the old landing URL, etc.
            doi = existingID;
		    log.info("Not automated");
		    log.info("Existing DOI: " + doi);
		    log.info("Target URL: " + target);

		    metadata.put(DataCiteMetadataTemplate.identifier,doi);
		    log.info("Metadata: " + new DataCiteMetadataTemplate(metadata).getMetadata());

			log.debug("Updating metadata for: " + existingID);
			
			client.close();
		} else if ((existingID == null) || !allowUpdates) {
			
			//doi = ezid.mintIdentifier(shoulder, metadata);
			//MAYBE: provide a shoulder on the DataCite /metadata call and it will generate an identifier
			doi = shoulder +  RandomStringUtils.randomAlphanumeric(6).toUpperCase();
			log.debug("Generating new PID : " + doi);
			metadata.put(DataCiteMetadataTemplate.identifier,doi);
			 log.debug(client.postMetadata(new DataCiteMetadataTemplate(metadata).getMetadata()));
log.debug("Sending URL: " + target);
			 log.debug(client.putUrl(doi, target));
 
	        
		} else {
			log.warn("Request to update an existing DOI that does not match requested shoulder: "
					+ existingID + " : " + shoulder);
			throw new SEADException(
					"Cannot update doi due to shoulder conflict");
		}
		log.debug("Generated/Updated DOI: http://doi.org/" + doi);
		// Use newer doi.org resolver
		return "http://doi.org/" + doi;
	}

    private static Set<String> getValues(Object entry) {
        Set<String> values = new HashSet<String>();
        if (entry instanceof String) {
            values.add((String) entry);
        } else if (entry instanceof JSONArray) {

            JSONArray jsonArray = (JSONArray) entry;
            for(int i = 0 ; i < jsonArray.length() ; i++) {
                Object val = jsonArray.get(i);
                if (val instanceof String) {
                    values.add((String) val);
                }
            }

            /*Iterator<Object> iter = ((JSONArray) entry).iterator();
            while (iter.hasNext()) {
                Object val = iter.next();
                if (val instanceof String) {
                    values.add((String) val);
                }
            }*/
        }
        return values;
    }

    public static String getID() {
		return repoID;
	}

	public static String getC3PRAddress() {
		return props.getProperty("c3pr.address");
	}
}
