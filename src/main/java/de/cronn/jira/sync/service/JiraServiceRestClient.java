package de.cronn.jira.sync.service;

import static de.cronn.jira.sync.service.JiraServiceCacheConfig.*;

import java.io.IOException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.annotation.PreDestroy;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.apache.http.client.HttpClient;
import org.apache.http.conn.socket.LayeredConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.ssl.SSLContexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.client.RestTemplate;

import de.cronn.jira.sync.JiraSyncException;
import de.cronn.jira.sync.config.BasicAuthentication;
import de.cronn.jira.sync.config.JiraConnectionProperties;
import de.cronn.jira.sync.domain.JiraComment;
import de.cronn.jira.sync.domain.JiraField;
import de.cronn.jira.sync.domain.JiraFieldList;
import de.cronn.jira.sync.domain.JiraFilterResult;
import de.cronn.jira.sync.domain.JiraIssue;
import de.cronn.jira.sync.domain.JiraIssueUpdate;
import de.cronn.jira.sync.domain.JiraLinkIcon;
import de.cronn.jira.sync.domain.JiraLoginRequest;
import de.cronn.jira.sync.domain.JiraLoginResponse;
import de.cronn.jira.sync.domain.JiraPriority;
import de.cronn.jira.sync.domain.JiraPriorityList;
import de.cronn.jira.sync.domain.JiraProject;
import de.cronn.jira.sync.domain.JiraRemoteLink;
import de.cronn.jira.sync.domain.JiraRemoteLinkObject;
import de.cronn.jira.sync.domain.JiraRemoteLinks;
import de.cronn.jira.sync.domain.JiraResolution;
import de.cronn.jira.sync.domain.JiraResolutionList;
import de.cronn.jira.sync.domain.JiraSearchResult;
import de.cronn.jira.sync.domain.JiraServerInfo;
import de.cronn.jira.sync.domain.JiraTransition;
import de.cronn.jira.sync.domain.JiraTransitions;
import de.cronn.jira.sync.domain.JiraUser;
import de.cronn.jira.sync.domain.JiraVersion;
import de.cronn.jira.sync.domain.JiraVersionsList;
import de.cronn.jira.sync.domain.WellKnownJiraField;
import de.cronn.proxy.ssh.SshProxy;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class JiraServiceRestClient implements JiraService {

	private static final Logger log = LoggerFactory.getLogger(JiraServiceRestClient.class);

	private final RestTemplateBuilder restTemplateBuilder;

	private RestTemplate restTemplate;
	private JiraConnectionProperties jiraConnectionProperties;
	private SshProxy sshProxy;
	private URL url;

	public JiraServiceRestClient(RestTemplateBuilder restTemplateBuilder) {
		this.restTemplateBuilder = restTemplateBuilder;
	}

	private RestTemplate createRestTemplate(JiraConnectionProperties jiraConnectionProperties) {
		RestTemplateBuilder builder = restTemplateBuilder.errorHandler(new JiraRestResponseErrorHandler());
		BasicAuthentication basicAuth = jiraConnectionProperties.getBasicAuth();
		if (basicAuth != null) {
			builder = builder.basicAuthorization(basicAuth.getUsername(), basicAuth.getPassword());
		}

		if (jiraConnectionProperties.getSslTrustStore() != null) {
			builder = builder.requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient(jiraConnectionProperties)));
		}

		return builder.build();
	}

	private HttpClient httpClient(JiraConnectionProperties jiraConnectionProperties) {
		try {
			SSLContext sslContext = SSLContexts.custom()
				.loadTrustMaterial(
					jiraConnectionProperties.getSslTrustStore().getFile(),
					jiraConnectionProperties.getSslTrustStorePassword())
				.build();

			HostnameVerifier hostnameVerifier = SSLConnectionSocketFactory.getDefaultHostnameVerifier();
			SSLSocketFactory socketFactory = sslContext.getSocketFactory();
			LayeredConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(socketFactory, hostnameVerifier);

			if (jiraConnectionProperties.getSshJumpHost() != null) {
				sshProxy = new SshProxy();
				sslSocketFactory = new SshTunnelSslSocketFactory(sshProxy, sslSocketFactory, jiraConnectionProperties.getSshJumpHost());
			}

			return HttpClientBuilder.create()
				.setSSLSocketFactory(sslSocketFactory)
				.build();
		} catch (GeneralSecurityException | IOException e) {
			throw new JiraSyncException("Failed to build custom http client", e);
		}
	}

	@Override
	@CacheEvict(cacheNames = {
		CACHE_NAME_PRIORITIES,
		CACHE_NAME_SERVER_INFO,
		CACHE_NAME_MYSELF,
		CACHE_NAME_USERS,
		CACHE_NAME_PROJECTS,
		CACHE_NAME_VERSIONS,
		CACHE_NAME_RESOLUTIONS,
		CACHE_NAME_FIELDS,
		CACHE_NAME_REMOTE_LINKS }, allEntries = true)
	public void evictAllCaches() {
		log.info("all caches evicted");
	}

	@Override
	public void login(JiraConnectionProperties jiraConnectionProperties) {
		this.jiraConnectionProperties = jiraConnectionProperties;
		validate(jiraConnectionProperties);
		this.url = jiraConnectionProperties.getUrl();
		this.restTemplate = createRestTemplate(jiraConnectionProperties);
		JiraLoginRequest loginRequest = new JiraLoginRequest(jiraConnectionProperties.getUsername(), jiraConnectionProperties.getPassword());
		restTemplate.postForObject(restUrl("/rest/auth/1/session"), loginRequest, JiraLoginResponse.class);
	}

	@Override
	public void logout() {
		if (restTemplate != null) {
			restTemplate.delete(restUrl("/rest/auth/1/session"));
			restTemplate = null;
		}
		jiraConnectionProperties = null;
		restTemplate = null;
		url = null;
		if (sshProxy != null) {
			sshProxy.close();
			sshProxy = null;
		}
	}

	@Override
	@PreDestroy
	public void close() {
		logout();
	}

	@Override
	@Cacheable(value = CACHE_NAME_SERVER_INFO, key = "#root.target.url")
	public JiraServerInfo getServerInfo() {
		log.debug("[{}], fetching server info", getUrl());
		return getForObject("/rest/api/2/serverInfo", JiraServerInfo.class);
	}

	@Override
	@Cacheable(value = CACHE_NAME_MYSELF, key = "#root.target.url")
	public JiraUser getMyself() {
		log.debug("[{}], fetching myself", getUrl());
		return getForObject("/rest/api/2/myself", JiraUser.class);
	}

	@Override
	@Cacheable(value = CACHE_NAME_USERS, key = "{ #root.target.url, #username }")
	public JiraUser getUserByName(String username) {
		try {
			return getForObject("/rest/api/2/user/?username={username}", JiraUser.class, username);
		} catch (JiraResourceNotFoundException e) {
			log.debug("user '{}' not found: {}", e.getMessage());
			return null;
		}
	}

	@Override
	public JiraIssue getIssueByKey(String issueKey) {
		validateIssueKey(issueKey);
		return getForObject("/rest/api/2/issue/{key}", JiraIssue.class, issueKey);
	}

	@Override
	@Cacheable(value = CACHE_NAME_PROJECTS, key = "{ #root.target.url, #projectKey }")
	public JiraProject getProjectByKey(String projectKey) {
		validateProjectKey(projectKey);
		log.debug("[{}], fetching project {}", getUrl(), projectKey);
		return getForObject("/rest/api/2/project/{key}", JiraProject.class, projectKey);
	}

	@Override
	@Cacheable(value = CACHE_NAME_VERSIONS, key = "{ #root.target.url, #projectKey }")
	public List<JiraVersion> getVersions(String projectKey) {
		validateProjectKey(projectKey);
		log.debug("[{}] fetching versions for project {}", getUrl(), projectKey);
		return getForObject("/rest/api/2/project/{key}/versions", JiraVersionsList.class, projectKey);
	}

	@Override
	@Cacheable(value = CACHE_NAME_PRIORITIES, key = "#root.target.url")
	public List<JiraPriority> getPriorities() {
		log.debug("[{}] fetching priorities", getUrl());
		return getForObject("/rest/api/2/priority", JiraPriorityList.class);
	}

	@Override
	@Cacheable(value = CACHE_NAME_RESOLUTIONS, key = "#root.target.url")
	public List<JiraResolution> getResolutions() {
		log.debug("[{}] fetching resolutions", getUrl());
		return getForObject("/rest/api/2/resolution", JiraResolutionList.class);
	}

	@Override
	@Cacheable(value = CACHE_NAME_FIELDS, key = "#root.target.url")
	public List<JiraField> getFields() {
		log.debug("[{}] fetching fields", getUrl());
		return getForObject("/rest/api/2/field", JiraFieldList.class);
	}

	@Override
	public List<JiraIssue> getIssuesByFilterId(String filterId, Collection<String> customFields) {
		log.debug("fetching filter {}", filterId);
		JiraFilterResult filter = getForObject("/rest/api/2/filter/{id}", JiraFilterResult.class, filterId);
		log.debug("fetching issues by JQL '{}'", filter.getJql());
		String fieldsToFetch = getFieldsToFetch(customFields);
		JiraSearchResult searchResult = getForObject("/rest/api/2/search?jql={jql}&maxResults=100&fields=" + fieldsToFetch, JiraSearchResult.class, filter.getJql());
		log.info("got {} issues", searchResult.getTotal());
		if (searchResult.getTotal() > searchResult.getMaxResults()) {
			throw new IllegalStateException("Paging not yet implemented");
		}
		return searchResult.getIssues();
	}

	private String getFieldsToFetch(Collection<String> customFields) {
		List<String> fieldsToFetch = new ArrayList<>();
		for (WellKnownJiraField knownJiraField : WellKnownJiraField.values()) {
			fieldsToFetch.add(knownJiraField.getName());
		}
		List<JiraField> fields = getFields();
		for (String field : customFields) {
			fieldsToFetch.add(findFieldId(fields, field));
		}
		return fieldsToFetch.stream().collect(Collectors.joining(","));
	}

	private String findFieldId(List<JiraField> fields, String fieldName) {
		for (JiraField field : fields) {
			if (field.getName().equals(fieldName)) {
				return field.getId();
			}
		}
		throw new JiraSyncException("Field " + fieldName + " not found in " + this);
	}

	private <T> T getForObject(String url, Class<T> responseType, Object... urlVariables) {
		return restTemplate.getForObject(restUrl(url), responseType, urlVariables);
	}

	@Override
	@Cacheable(value = CACHE_NAME_REMOTE_LINKS, key = "{ #root.target.url, #issueKey, #issueUpdated }")
	public List<JiraRemoteLink> getRemoteLinks(String issueKey, Instant issueUpdated) {
		validateIssueKey(issueKey);
		return getForObject("/rest/api/2/issue/{issueId}/remotelink", JiraRemoteLinks.class, issueKey);
	}

	@Override
	public List<JiraTransition> getTransitions(String issueKey) {
		validateIssueKey(issueKey);
		JiraTransitions transitions = getForObject("/rest/api/2/issue/{issueId}/transitions", JiraTransitions.class, issueKey);
		return transitions.getTransitions();
	}

	@Override
	public JiraIssue createIssue(JiraIssue issue) {
		JiraIssue createdIssue = restTemplate.postForObject(restUrl("/rest/api/2/issue"), issue, JiraIssue.class);
		log.debug("created issue: {}", createdIssue);
		return createdIssue;
	}

	@Override
	@CacheEvict(cacheNames = CACHE_NAME_REMOTE_LINKS, allEntries = true)
	public void addRemoteLink(JiraIssue fromIssue, JiraIssue toIssue, JiraService toJiraService, URL remoteLinkIcon) {
		validateIssueKey(fromIssue.getKey());
		validateIssueKey(toIssue.getKey());
		log.debug("adding remote from {} to {}", fromIssue.getKey(), toIssue.getKey());
		JiraServerInfo remoteServerInfo = toJiraService.getServerInfo();
		String remoteUrl = remoteServerInfo.getBaseUrl() + "/browse/" + toIssue.getKey();
		JiraRemoteLink jiraRemoteLink = new JiraRemoteLink(remoteUrl);
		JiraRemoteLinkObject remoteLinkObject = jiraRemoteLink.getObject();
		remoteLinkObject.setTitle(remoteServerInfo.getServerTitle() + ": " + toIssue.getKey());
		remoteLinkObject.setIcon(new JiraLinkIcon(remoteLinkIcon));
		restTemplate.postForObject(restUrl("/rest/api/2/issue/{issueId}/remotelink"), jiraRemoteLink, Map.class, fromIssue.getKey());
	}

	@Override
	public JiraComment addComment(String issueKey, String commentText) {
		validateIssueKey(issueKey);
		Assert.hasText(commentText);
		JiraComment comment = new JiraComment(commentText);
		return restTemplate.postForObject(restUrl("/rest/api/2/issue/{issueId}/comment"), comment, JiraComment.class, issueKey);
	}

	@Override
	public void updateComment(String issueKey, String commentId, String commentText) {
		validateIssueKey(issueKey);
		Assert.hasText(commentText);
		JiraComment comment = new JiraComment(commentText);
		restTemplate.put(restUrl("/rest/api/2/issue/{issueId}/comment/{commentId}"), comment, issueKey, commentId);
	}

	@Override
	public void updateIssue(String issueKey, JiraIssueUpdate issueUpdate) {
		validateIssueKey(issueKey);
		restTemplate.put(restUrl("/rest/api/2/issue/{issueId}"), issueUpdate, issueKey);
	}

	@Override
	public void transitionIssue(String issueKey, JiraIssueUpdate issueUpdate) {
		validateIssueKey(issueKey);
		restTemplate.postForObject(restUrl("/rest/api/2/issue/{issueId}/transitions"), issueUpdate, Void.class, issueKey);
	}

	private String restUrl(String url) {
		return getUrl() + url;
	}

	@Override
	public URL getUrl() {
		return url;
	}

	private void validate(JiraConnectionProperties jiraConnectionProperties) {
		Assert.notNull(jiraConnectionProperties.getUrl(), "url is missing");
		Assert.notNull(jiraConnectionProperties.getUsername(), "username is missing");
		Assert.notNull(jiraConnectionProperties.getPassword(), "password is missing");
	}

	private void validateIssueKey(String issueKey) {
		Assert.hasText(issueKey, "issueKey must not be empty");
	}

	private void validateProjectKey(String projectKey) {
		Assert.hasText(projectKey, "projectKey must not be empty");
	}

	@Override
	public String toString() {
		return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
			.append("jiraConnectionProperties", jiraConnectionProperties)
			.toString();
	}
}
