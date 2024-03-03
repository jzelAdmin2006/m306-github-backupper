package com.jzel.toughvault.integration.service;

import static java.util.Objects.requireNonNull;
import static okhttp3.MediaType.get;

import com.jzel.toughvault.business.domain.github.Auth;
import com.jzel.toughvault.business.service.RepoService;
import com.jzel.toughvault.integration.adapter.model.GitHubEmailDto;
import com.jzel.toughvault.integration.adapter.model.GitHubGraphQLDto;
import com.jzel.toughvault.integration.adapter.model.GitHubGraphQLDto.RepositoryNodeDto;
import com.nimbusds.jose.shaded.gson.Gson;
import com.nimbusds.jose.shaded.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.AllArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request.Builder;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class GitHubService {

  public static final Type GITHUB_EMAILS_ARRAY_TYPE = new TypeToken<List<GitHubEmailDto>>() {
  }.getType();
  private static final String EMAILS_URL = "https://api.github.com/user/emails";
  private static final MediaType JSON = get("application/json; charset=utf-8");
  private final Auth auth;
  private final Gson gson;
  private final OkHttpClient client;
  private final RepoService repoService;
  private final IntegrationMapperService mapperService;

  public String getPrimaryEmail(String token) throws IOException {
    try (Response response = client.newCall(addAuthorization(token, new Builder().url(EMAILS_URL)
        .get())
        .build()).execute()) {
      if (response.isSuccessful()) {
        List<GitHubEmailDto> emailList = gson.fromJson(requireNonNull(response.body()).string(),
            GITHUB_EMAILS_ARRAY_TYPE);
        return emailList.stream().filter(GitHubEmailDto::isPrimary).findFirst().orElseThrow().getEmail();
      } else {
        throw new IOException("Failed to fetch tokens: " + requireNonNull(response.body()).string());
      }
    }
  }

  @Scheduled(cron = "0 0/5 * * * *")
  public void scanForGitHubChanges() throws IOException {
    String login = "jzelAdmin2006"; // TODO make this dynamic
    GitHubGraphQLDto gitHubResponse = gson.fromJson(executeGraphQlQuery(getQuery(login, null)),
        GitHubGraphQLDto.class);
    List<RepositoryNodeDto> nodes = new ArrayList<>(
        gitHubResponse.getData().getUser().getRepositoriesContributedTo().getNodes());
    while (gitHubResponse.getData().getUser().getRepositoriesContributedTo().getPageInfo().isHasNextPage()) {
      gitHubResponse = gson.fromJson(
          executeGraphQlQuery(
              getQuery(login, gitHubResponse.getData().getUser().getRepositoriesContributedTo().getPageInfo()
                  .getEndCursor())), GitHubGraphQLDto.class);
      nodes.addAll(gitHubResponse.getData().getUser().getRepositoriesContributedTo().getNodes());
    }
    repoService.updateAllRepoEntries(nodes.stream().filter(Objects::nonNull).map(mapperService::fromDto).toList());
  }

  private String executeGraphQlQuery(String query) throws IOException {
    try (Response response = client.newCall(addAuthorization(new Builder()
        .url("https://api.github.com/graphql")
        .post(RequestBody.create(query, JSON))).build()).execute()) {
      if (response.isSuccessful()) {
        return requireNonNull(response.body()).string();
      } else {
        throw new IOException("Unexpected code " + response);
      }
    }
  }

  private Builder addAuthorization(String token, Builder builder) {
    return builder.addHeader("Authorization", "Bearer " + token);
  }

  private Builder addAuthorization(Builder builder) {
    return builder.addHeader("Authorization", "Bearer " + auth.getAccessToken().orElseThrow());
  }

  private String getQuery(@NotNull String login, @Nullable String cursor) {
    return
        "{ \"query\": \"query getContributions($login: String!, $contributionTypes: [RepositoryContributionType], $afterCursor: String) { user(login: $login) { repositoriesContributedTo(contributionTypes: $contributionTypes, first: 100, after: $afterCursor, includeUserRepositories: true, orderBy: {field: PUSHED_AT, direction: DESC}) { pageInfo { hasNextPage, endCursor } nodes { nameWithOwner, pushedAt } } } }\", \"variables\": { \"login\": \"%s\", \"contributionTypes\": [\"COMMIT\", \"REPOSITORY\"], \"afterCursor\": %s } }".formatted(
            login, cursor == null ? "null" : "\"%s\"".formatted(cursor)); // TODO improve readability of GraphQL query
  }
}