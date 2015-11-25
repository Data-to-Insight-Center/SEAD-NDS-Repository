/*
 * Copyright 2015 University of Michigan
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
 */

package org.sead.nds.repository;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.compress.archivers.zip.ParallelScatterZipCreator;
import org.apache.commons.compress.archivers.zip.ScatterZipOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntryRequest;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.parallel.InputStreamSupplier;
import org.apache.commons.lang3.text.WordUtils;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.sead.nds.repository.util.FileUtils;

public class BagGenerator {

	private static final Logger log = Logger.getLogger(BagGenerator.class);

	private ParallelScatterZipCreator scatterZipCreator = new ParallelScatterZipCreator();
	private ScatterZipOutputStream dirs = null;
	
	private JSONArray aggregates = null;
	private ArrayList<String> resourceIndex = null;
	private Boolean[] resourceUsed = null;
	private HashMap<String, String> pidMap = new LinkedHashMap<String, String>();
	private HashMap<String, String> sha1Map = new LinkedHashMap<String, String>();

	private String bagPath = null;

	private C3PRPubRequestFacade RO = null;
	public BagGenerator(C3PRPubRequestFacade ro) {
		RO=ro;
		this.bagPath = Repository.getDataPath();
	}

	/*
	 * Full workflow to generate new BagIt bag from ORE Map Url and Bag
	 * path/name on disk
	 * 
	 * @return success true/false
	 */
	public boolean generateBag() {
		log.debug("Generating: Bag to the Future!");
		log.debug("BagPath: " + getBagPath());
		JSONObject pubRequest = RO.getPublicationRequest();
		try {

			File tmp = File.createTempFile("sead-scatter-dirs", "tmp");
			dirs = ScatterZipOutputStream.fileBased(tmp);

			JSONObject oremap = RO.getOREMap();
			JSONObject aggregation = oremap.getJSONObject("describes");
			String bagID = aggregation.getString("Identifier");
			String bagName = bagID;
			try {
				// Create valid filename from identifier and extend path with
				// two levels of hash-based subdirs to help distribute files
				bagName = getValidNameAndUpdatePath(bagName);
			} catch (Exception e) {
				log.error("Couldn't create valid filename: "
						+ e.getLocalizedMessage());
				return false;
			}
			// Create data dir in bag, also creates parent bagName dir
			String currentPath = bagName + "/data/";
			createDir(currentPath);

			aggregates = aggregation.getJSONArray("aggregates");

			if (aggregates != null) {
				// Add container and data entries
				// Setup global index of the aggregation and all aggregated
				// resources by Identifier
				resourceIndex = indexResources(bagID, aggregates);
				// Setup global list of succeed(true), fail(false), notused
				// (null) flags
				resourceUsed = new Boolean[aggregates.length() + 1];
				// Process current container (the aggregation itself) and its
				// children
				processContainer(aggregation, currentPath);
			}
			// Create mainifest files
			// pid-mapping.txt - a DataOne recommendation to connect ids and
			// in-bag path/names
			StringBuffer pidStringBuffer = new StringBuffer();
			boolean first = true;
			for (Entry<String, String> pidEntry : pidMap.entrySet()) {
				if (!first) {
					pidStringBuffer.append("\n");
				} else {
					first = false;
				}
				pidStringBuffer.append(pidEntry.getKey() + " "
						+ pidEntry.getValue());
			}
			createFileFromString(bagName + "/pid-mapping.txt",
					pidStringBuffer.toString());
			// SHA1 hash manifest - a hash manifest for md5 or sha1 is required
			// by Bagit spec
			StringBuffer sha1StringBuffer = new StringBuffer();
			first = true;
			for (Entry<String, String> sha1Entry : sha1Map.entrySet()) {
				if (!first) {
					sha1StringBuffer.append("\n");
				} else {
					first = false;
				}
				sha1StringBuffer.append(sha1Entry.getKey() + " "
						+ sha1Entry.getValue());
			}
			createFileFromString(bagName + "/manifest-sha1.txt",
					sha1StringBuffer.toString());
			// bagit.txt - Required by spec
			createFileFromString(bagName + "/bagit.txt",
					"BagIt-Version: 0.97\nTag-File-Character-Encoding: UTF-8");

			// Generate DOI:
			oremap.getJSONObject("describes").put("External Identifier",
					Repository.createDOIForRO(bagName, RO));

			aggregation.put("Creators",
					RO.expandPeople(RO.normalizeValues(oremap.getJSONObject("describes").get("Creator"))));
			// Serialize oremap itself (pretty printed) - SEAD recommendation
			// (DataOne distributes metadata files within the bag
			// FixMe - add missing hash values if needed and update context
			// (read and cache files or read twice?)
			createFileFromString(bagName + "/oremap.jsonld.txt",
					oremap.toString(2));
			
			//Add a bag-info file
			createFileFromString(bagName + "/bag-info.txt",
					generateInfoFile(pubRequest, oremap));

			log.debug("Creating bag: " + getBagPath());
			// Create the bag file on disk
			File parent = new File(getBagPath());
			parent.mkdirs();
			File result = new File(getBagPath(), bagName + ".zip");
			// Create an output stream backed by the file
			ZipArchiveOutputStream zipArchiveOutputStream = new ZipArchiveOutputStream(
					result);

			// Add all the waiting contents - dirs created first, then data
			// files
			// are retrieved via URLs in parallel (defaults to one thread per
			// processor)
			// directly to the zip file
			writeTo(zipArchiveOutputStream);
			// Finish
			zipArchiveOutputStream.close();

			ZipFile zf = new ZipFile(result);

			// Validate oremap - all entries are part of the collection
			for (int i = 0; i < resourceUsed.length; i++) {
				Boolean b = resourceUsed[i];
				if (b == null) {
					RO.sendStatus("Problem",
							pidMap.get(resourceIndex.get(i)) + " was not used");
				} else if (b == false) {
					RO.sendStatus("Problem",
							pidMap.get(resourceIndex.get(i))
									+ " was not included successfully");
				} else {
					// Successfully included - now check for hash value and
					// generate if needed
					if(i>0) { //Not root container
					if (!sha1Map
							.containsValue(pidMap.get(resourceIndex.get(i)))) {
						 
						if(!RO.childIsContainer(i-1))
						log.debug("Missing sha1 hash for: "
								+ resourceIndex.get(i));
						// FixMe - actually generate it before adding the oremap
						// to the zip
					}
					}
				}

			}

			// Run a confirmation test - should verify all files and sha1s
			checkFiles(sha1Map, zf);
			zf.close();
			return true;

		} catch (Exception e) {
			log.error(e.getLocalizedMessage());
			e.printStackTrace();
			RO.sendStatus("Failure",
					"Processing failure during Bagit file creation");
			return false;
		}
	}

	private String getValidNameAndUpdatePath(String bagName)
			throws NoSuchAlgorithmException, IOException {
		String pathString = DigestUtils.sha1Hex(bagName);

		// Two level hash-based distribution o files
		bagPath = Paths.get(bagPath, pathString.substring(0, 2),
				pathString.substring(2, 4)).toString();
		// Create known-good filename
		return bagName.replaceAll("\\W+", "_");
	}

	private void processContainer(JSONObject item, String currentPath) {
		JSONArray children = RO.getChildren(item);
		currentPath = currentPath + item.getString("Title") + "/";
		try {
			createDir(currentPath);
			// Add containers to pid map and mark as 'used', but no sha1 hash
			// value
			resourceUsed[resourceIndex.indexOf(item.getString("Identifier"))] = true;
			pidMap.put(item.getString("Identifier"), currentPath);
		} catch (Exception e) {
			resourceUsed[resourceIndex.indexOf(item.getString("Identifier"))] = false;
			e.printStackTrace();
		}
		for (int i = 0; i < children.length(); i++) {

			// Find the ith child in the overall array of aggregated
			// resources
			String childId = children.getString(i);
			// Clowder Kludge
			if (childId.length() == 24) {
				childId = "urn:uuid:" + childId;
			}
			int index = resourceIndex.indexOf(childId);
			if (resourceUsed[index] != null) {
				System.out.println("Warning: reusing resource " + index);
			}
			resourceUsed[index] = true;
			// Aggregation is at index 0, so need to shift by 1 for aggregates
			// entries
			JSONObject child = aggregates.getJSONObject(index - 1);
			if (RO.childIsContainer(index-1)) {
				// create dir and process children
				processContainer(child, currentPath);
			} else {
				// add item
				String dataUrl = (String) child.get("similarTo");
				String childPath = currentPath + (String) child.get("Title");
				try {
					log.debug("Requesting: " + childPath + " from " + dataUrl);
					createFileFromURL(childPath, dataUrl);
				} catch (Exception e) {
					resourceUsed[index] = false;
					e.printStackTrace();
				}
				// Check for nulls!
				pidMap.put(child.getString("Identifier"), childPath);
				// Check for nulls!
				if (child.has("SHA1 Hash")) {
					sha1Map.put(child.getString("SHA1 Hash"), childPath);
				}
			}
		}
	}

	private ArrayList<String> indexResources(String aggId, JSONArray aggregates) {
		ArrayList<String> l = new ArrayList<String>(aggregates.length() + 1);
		l.add(aggId);
		for (int i = 0; i < aggregates.length(); i++) {
			log.debug("Indexing : " + i + " " 
					+ aggregates.getJSONObject(i).getString("Identifier"));
			l.add(aggregates.getJSONObject(i).getString("Identifier"));
		}
		return l;
	}

	private void createDir(final String name) throws IOException,
			ExecutionException, InterruptedException {

		ZipArchiveEntry archiveEntry = new ZipArchiveEntry(name);
		archiveEntry.setMethod(ZipEntry.DEFLATED);
		InputStreamSupplier supp = new InputStreamSupplier() {
			public InputStream get() {
				return new ByteArrayInputStream(("").getBytes());
			}
		};

		addEntry(archiveEntry, supp);
	}

	private void createFileFromString(final String name, final String content)
			throws IOException, ExecutionException, InterruptedException {

		ZipArchiveEntry archiveEntry = new ZipArchiveEntry(name);
		archiveEntry.setMethod(ZipEntry.DEFLATED);
		InputStreamSupplier supp = new InputStreamSupplier() {
			public InputStream get() {
				try {
					return new ByteArrayInputStream(content.getBytes("UTF-8"));
				} catch (UnsupportedEncodingException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				return null;
			}
		};

		addEntry(archiveEntry, supp);
	}

	private void createFileFromURL(final String name, final String uri)
			throws IOException, ExecutionException, InterruptedException {

		ZipArchiveEntry archiveEntry = new ZipArchiveEntry(name);
		archiveEntry.setMethod(ZipEntry.DEFLATED);
		InputStreamSupplier supp = RO.getInputStreamSupplier(uri);
		addEntry(archiveEntry, supp);
	}

	private void checkFiles(HashMap<String, String> sha1Map2, ZipFile zf) {
		for (Entry<String, String> entry : sha1Map2.entrySet()) {
			if (!hasValidFileHash(entry.getKey(), entry.getValue(), zf)) {
				RO.sendStatus("Problem", "Hash for " + entry.getValue()
						+ " is incorrect.");
			}
		}
	}

	private boolean hasValidFileHash(String hash, String name, ZipFile zf) {
		boolean good = false;
		String realHash = generateFileHash(name, zf);
		if (hash.equals(realHash)) {
			good = true;
			log.debug("Valid hash for " + name);
		}
		return good;
	}

	private String generateFileHash(String name, ZipFile zf) {

		ZipArchiveEntry archiveEntry1 = zf.getEntry(name);
		long start = System.currentTimeMillis();
		InputStream inputStream;
		String realHash = null;
		try {
			inputStream = zf.getInputStream(archiveEntry1);

			realHash = DigestUtils.sha1Hex(inputStream);

		} catch (ZipException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		log.debug("Retrieve/compute time = "
				+ (System.currentTimeMillis() - start) + " ms");
		return realHash;
	}

	public void addEntry(ZipArchiveEntry zipArchiveEntry,
			InputStreamSupplier streamSupplier) throws IOException {
		if (zipArchiveEntry.isDirectory() && !zipArchiveEntry.isUnixSymlink())
			dirs.addArchiveEntry(ZipArchiveEntryRequest
					.createZipArchiveEntryRequest(zipArchiveEntry,
							streamSupplier));
		else
			scatterZipCreator.addArchiveEntry(zipArchiveEntry, streamSupplier);
	}

	public void writeTo(ZipArchiveOutputStream zipArchiveOutputStream)
			throws IOException, ExecutionException, InterruptedException {
		dirs.writeTo(zipArchiveOutputStream);
		dirs.close();
		scatterZipCreator.writeTo(zipArchiveOutputStream);
	}

	public String getBagPath() {
		return bagPath;
	}


	static final String CRLF = "\r\n";

	private String generateInfoFile(JSONObject request, JSONObject map) {
		log.debug("Generating info file");
		StringBuffer info = new StringBuffer();

		// SEAD and Rights Holder?
		if (map.getJSONObject("describes").has("Primary Source")) {
			info.append("Source-Organization: ");
			info.append(map.getJSONObject("describes").getString(
					"Primary Source"));
			info.append(CRLF);
		}
		JSONArray contactsArray = new JSONArray();
		if (map.getJSONObject("describes").has("Contact")) {
			contactsArray = RO.expandPeople(RO.normalizeValues(map.getJSONObject(
					"describes").get("Contact")));
		} else {
			if (request.has("Rights Holder")) {
				contactsArray = RO.expandPeople(RO.normalizeValues(request
						.get("Rights Holder")));
			}
		}
		for (int i = 0; i < contactsArray.length(); i++) {
			info.append("Contact-Name: ");
			Object person = contactsArray.get(i);
			if (person instanceof String) {
				info.append((String) person);
				info.append(CRLF);

			} else {
				info.append(((JSONObject) person).getString("givenName")
						+ ((JSONObject) person).getString("familyName"));
				info.append(CRLF);
				if (((JSONObject) person).has("email")) {
					info.append("Contact-Email: ");
					info.append(((JSONObject) person).getString("email"));
					info.append(CRLF);
				}
			}
		}

		info.append("Source-Organization: ");
		info.append("SEAD (http://sead-data.net/)");
		info.append(CRLF);

		info.append("Organization-Address: ");
		info.append("University of Michigan, Ann Arbor, MI 48106");
		info.append(CRLF);

		info.append("Contact-Email: ");
		info.append("SEADdatanet@umich.edu");
		info.append(CRLF);

		info.append("External-Description: ");

		info.append(WordUtils.wrap(
				map.getJSONObject("describes").getString("Abstract"), 78, CRLF
						+ " ", true));
		info.append(CRLF);

		info.append("Bagging-Date: ");
		info.append((new SimpleDateFormat("yyyy-MM-dd").format(Calendar
				.getInstance().getTime())));
		info.append(CRLF);
		info.append("External-Identifier: ");
		info.append(map.getJSONObject("describes").getString(
				"External Identifier"));
		info.append(CRLF);

		info.append("Bag-Size: ");
		info.append(FileUtils.byteCountToDisplaySize(Long.parseLong(request
				.getJSONObject("Aggregation Statistics")
				.getString("Total Size"))));
		info.append(CRLF);

		info.append("Payload-Oxum: ");
		info.append(request.getJSONObject("Aggregation Statistics").getString(
				"Total Size"));
		info.append(".");
		info.append(request.getJSONObject("Aggregation Statistics").getString(
				"Number of Datasets"));
		info.append(CRLF);

		info.append("Internal-Sender-Identifier: ");
		info.append(map.getJSONObject("describes").getString("Identifier"));
		info.append(CRLF);

		return info.toString();

	}
}
