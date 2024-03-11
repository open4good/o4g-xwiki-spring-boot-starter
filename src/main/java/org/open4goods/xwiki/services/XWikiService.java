package org.open4goods.xwiki.services;


import java.lang.reflect.Array;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.open4goods.xwiki.config.XWikiRelations;
import org.open4goods.xwiki.config.XWikiResourcesPath;
import org.open4goods.xwiki.config.XWikiServiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.UnknownHttpStatusCodeException;
import org.xwiki.rest.model.jaxb.Attachment;
import org.xwiki.rest.model.jaxb.Attachments;
import org.xwiki.rest.model.jaxb.ObjectSummary;
import org.xwiki.rest.model.jaxb.Objects;
import org.xwiki.rest.model.jaxb.Page;
import org.xwiki.rest.model.jaxb.PageSummary;
import org.xwiki.rest.model.jaxb.Pages;
import org.xwiki.rest.model.jaxb.SearchResult;
import org.xwiki.rest.model.jaxb.SearchResults;


/**
 * This service handles XWiki rest services :
 * @author Thierry.Ledan
 */                 
public class XWikiService {

	private static Logger logger = LoggerFactory.getLogger(XWikiService.class);
	
	private XWikiServiceProperties xWikiProperties;
	private XWikiServiceHelper helper;
	private XWikiResourcesPath resourcesPathManager;
	
	@Autowired
	private RestTemplateBuilder localRestTemplateBuilder;
	
	public XWikiService (RestTemplate restTemplate, XWikiServiceProperties xWikiProperties) throws Exception {
		this.xWikiProperties = xWikiProperties;
		helper = new XWikiServiceHelper(restTemplate, xWikiProperties);
		this.resourcesPathManager = new XWikiResourcesPath(xWikiProperties.getBaseUrl(), xWikiProperties.getApiEntrypoint(), xWikiProperties.apiWiki);
		
		// get all available wikis and check that the targeted one exists
		if( xWikiProperties.getApiWiki() == null || ! helper.checkWikiExists(xWikiProperties.getApiWiki()) ) {
			throw new Exception("The targeted wiki " + xWikiProperties.getApiWiki() + " does not exist");
		}
	}
	
	
	/**
	 * 
	 * @param space
	 * @param name
	 * @return
	 */
	public Page getPage(String space, String name) {
		Page page = null;
		String endpoint = resourcesPathManager.getPageEndpoint(space, name);	
		page = helper.mapPage(endpoint);
		return page;
	}

	/**
	 * 
	 * @param space
	 * @return
	 */
	public Pages getPages(String space) {
		String uri = resourcesPathManager.getPagesEndpoint(space);
		return helper.mapPages(uri);
	}
		
	/**
	 * Retrieve all 'Page' associated to a space
	 * with properties and attachments (disabled as default)
	 * @param space
	 * @return A List of 'Page' object, could be empty, never null
	 */
	public List<Page> getPagesList(String space) {
		
		Pages pages = null;
		List<Page> pagesList = new ArrayList<Page>();
		String endpoint = resourcesPathManager.getPagesEndpoint(space);
		
		pages = helper.mapPages(endpoint);
		
		// Loop on PageSummary list in order to create Page list
		if( pages != null && !pages.getPageSummaries().isEmpty() ) {
			
			Page tempPage = null;
			
			for(PageSummary p: pages.getPageSummaries()) {
				
				String pageUrl = null;
				
				// get page endpoint
				pageUrl =  helper.getHref(XWikiRelations.REL_PAGE, p.getLinks());
				
				// add request param to return fields that are disabled by default
				// TODO: mapping issue: field 'properrties' does not exists in the object 'ObjectSummary'
//				pageUrl = helper.addQueryParam(pageUrl, "class", "true");
//				pageUrl = helper.addQueryParam(pageUrl, "objects", "true");
//				pageUrl = helper.addQueryParam(pageUrl, "attachments", "true");
				tempPage = helper.mapPage(pageUrl);
				if( tempPage != null ) {
					pagesList.add(tempPage);

					// fetch attachments
					Attachments attachments = helper.getAttachments(tempPage);
					if( attachments != null && attachments.getAttachments() != null && attachments.getAttachments().size()  > 0 ) {
						// update url (scheme, query params..) according to starter properties
						for(Attachment attachment: attachments.getAttachments()) {
							attachment.setXwikiAbsoluteUrl(helper.updateUrlScheme(attachment.getXwikiAbsoluteUrl()));
							attachment.setXwikiRelativeUrl(helper.updateUrlScheme(attachment.getXwikiRelativeUrl()));
						}
						tempPage.setAttachments(attachments);
					}	
					
					// fetch objects (properties, ...)
					Objects objects = helper.getPageObjects(tempPage);
					if( objects != null ) {
						tempPage.setObjects(objects);
					}
					
					// fetch classes
					
				}
			}
		}

		
		return pagesList;
	}
	
	
	/**
	 * Get properties related to Page with name 'pageName' in space 'spaceName'
	 * 
	 * @param spaceName space related to 'page'
	 * @param pageName name of 'page'
	 * @return
	 */
	public Map<String,String> getProperties(String spaceName, String pageName) {
		Map<String,String> props = new HashMap<String, String>();
		String endpoint = resourcesPathManager.getPageEndpoint(spaceName, pageName);
		Page page = helper.mapPage(endpoint);
		if(page != null) {
			props = helper.getProperties(page);
		}
		return props;
	}
	
	/**
	 * Get properties from Page 'page'
	 * 
	 * @param page
	 * @return 
	 */
	public Map<String,String> getProperties(Page page) {
		Map<String,String> props = new HashMap<String, String>();
		if(page != null) {
			props = helper.getProperties(page);
		}
		return props;
	}
	
	
	//////////////////////////////
	//							// 									
	//  USERS - GROUPS - ROLES	//
	//							// 
	//////////////////////////////
	
	/**
	 * Get all groups pageName
	 * discard "XWikiGroupTemplate"  
	 * 
	 * @return
	 */
	public List<String> getGroupsName(){
		List<String> groups = new ArrayList<String>();
		SearchResults results = helper.mapSearchResults(resourcesPathManager.getGroupsEndpoint());
		if( results != null && !results.getSearchResults().isEmpty()) {
			for(SearchResult result: results.getSearchResults()) {
				if( !result.getPageName().contains("XWikiGroupTemplate") ) {
					groups.add(result.getPageName());
				}
			}
		}
		return groups;
	}
	
	
	/**
	 * Get users name for a group
	 * 
	 * scan objects summary, user's name is set in field "headline" with the prefix "XWiki."
	 * 
	 * @param groupPageName
	 * @return
	 */
	public List<String> getGroupUsers(String groupPageName) {
		// https://wiki.nudger.fr/rest/wikis/xwiki/spaces/XWiki/pages/SiteEditor/objects?media=json
		List<String> users = new ArrayList<String>();
		Objects objects = helper.getObjects(resourcesPathManager.getGroupUsers(groupPageName));
		if( objects != null && ! objects.getObjectSummaries().isEmpty() ) {
			for( ObjectSummary objectsummary: objects.getObjectSummaries() ) {
				if( ! objectsummary.getHeadline().isEmpty() ) {
					users.add(objectsummary.getHeadline().replaceAll("XWiki.", ""));
				}
			}
		}
		return users;
	}
	
	
	/**
	 * Get the User (Page object in xwiki) from username
	 * 
	 * @param userName
	 * @return
	 */
	public Page getUser(String userName) {
		Page page = null;
		String endpoint = resourcesPathManager.getPageEndpoint("", userName);	
		page = helper.mapPage(endpoint);
		return page;
	}
	
	
	/**
	 * Login on xwiki and return groups belonging to the current user
	 * 
	 * @param userName current username
	 * @param password
	 * @return List of groups belonging to the current user
	 * @throws Exception
	 */
	public List<String> login( String userName, String password) throws Exception {
		
		List<String> groups = null;
		String endpoint = resourcesPathManager.getCurrentUserGroupsEndpoint();
		
		RestTemplate localRestTemplate =  localRestTemplateBuilder.basicAuthentication(userName, password).build();
		ResponseEntity<String> response = null;

		// first clean url: url decoding, check scheme and add query params if needed
		String updatedEndpoint = helper.cleanUrl(endpoint);
		logger.info("request xwiki server with endpoint {}", updatedEndpoint);

		if(updatedEndpoint != null) {
			try {
				response = localRestTemplate.getForEntity(updatedEndpoint, String.class);
			} 
			// HTTP status 4xx
			catch(HttpClientErrorException e) {
				logger.warn("Client error - uri:{} - error:{}", updatedEndpoint, e.getStatusCode().toString());
				throw new Exception(e.getStatusText());
			} 
			// HTTP status 5xx
			catch(HttpServerErrorException e) {
				logger.warn("Server error - uri:{} - error:{}", updatedEndpoint, e.getStatusCode().toString());
				throw new Exception(e.getStatusText());
			} 
			//  unknown HTTP status
			catch(UnknownHttpStatusCodeException e) {
				logger.warn("Server error response  - uri:{} - error:{}", updatedEndpoint, e.getStatusCode().toString());
				throw new Exception("Login error");
			} 
			// other errors
			catch(Exception e) {
				logger.warn("Exception while trying to reach endpoint:{} - error:{}", updatedEndpoint, e.getMessage());
				throw new Exception("Login error");
			}
			
			// check response status code
			if (null != response && response.getStatusCode().is2xxSuccessful()) {
				try {
					// parse and get groups
					Document doc = Jsoup.parse(response.getBody());
					Element div = doc.getElementById("xwikicontent");
					String content = div.text();					
					logger.debug("Groups retrieved in html:" + content);
					groups = Arrays.asList(content.substring(1, content.length() - 1).split(","));
					
				} catch( Exception e ) {
					// TODO: how to manage this error: login succeedded but parsing error !!
					logger.warn("Exception while searching groups in html response: {}", response.getBody());
					throw new Exception("Groups parsing error");
				}
			} else {
				logger.warn("Response returns with status code:{} - for uri:{}", response.getStatusCode(), updatedEndpoint);
				response = null;
			}
		}
		return groups;
	}
	
	/**
	 * Get the user's properties
	 * 
	 * @param userName
	 * @return
	 */
	public Map<String,String> getUserProperties(Page user) {
		Map<String,String> properties = new HashMap<String, String>();
		if( user != null ) {
			// first get objects from Page
			String propertiesUri = helper.getHref(XWikiRelations.REL_OBJECT, user.getLinks());
			Objects objects = helper.getObjects(propertiesUri);
			// then get properties from objects (look for an object from class "XWikiUsers")
			properties =  helper.getProperties(objects, resourcesPathManager.getUsersClassName());
		}
		return properties;
	}
}
