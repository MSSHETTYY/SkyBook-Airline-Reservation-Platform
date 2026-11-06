# ADR-0004: CI/CD on GitHub Actions with GitHub Container Registry

- **Status:** Accepted
- **Date:** 2026-11-06
- **Deciders:** SkyBook architecture, repository owner

## Context

The original brief specified a GitLab CI/CD pipeline and the GitLab container registry. The
repository is hosted on **GitHub**, and a `.gitlab-ci.yml` file is never executed there. The
pipeline stages themselves are platform-neutral: build, unit test, integration test, SonarQube,
Trivy/OWASP scan, image build and push, Helm deploy.

## Decision

- Use **GitHub Actions** for CI/CD and **GHCR** (`ghcr.io`) for container images.
- Keep the stage order and quality gates from the brief. Each stage is a separate job, so a
  failure points at one concern.
- Keep pipeline logic in Maven, the Maven wrapper and scripts in the repository, and keep the
  workflow YAML a thin orchestration layer. Moving to GitLab CI later would then mean rewriting
  only the YAML.
- Branching follows **GitLab Flow** (a protected `main`, short-lived feature branches merged by
  pull request, and environment branches added when there is more than one deployment target).
  The flow does not depend on GitLab itself.

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| GitLab CI (mirror the repo to GitLab) | Matches the brief literally | Two remotes to keep in sync; pull requests and CI live in different places |
| Both pipelines | Shows both skill sets | Two pipelines to keep equivalent, which drift silently |

## Consequences

- Images are published as `ghcr.io/<owner>/skybook-<service>`, authenticated with the built-in
  `GITHUB_TOKEN`, so no long-lived registry secrets are needed.
- SonarQube runs as a container service in the workflow, or as SonarCloud's free tier for public
  repositories. That choice is made in Phase 6.
