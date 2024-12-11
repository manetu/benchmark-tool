# Copyright © Manetu, Inc.  All rights reserved

#-------------------------------------------------------------------------------
# Builder - compiles the primary microservice artifact
#-------------------------------------------------------------------------------
FROM registry.gitlab.com/manetu/tools/unified-builder:v3.0 as builder

WORKDIR /src

ARG TARGETARCH
ARG GITLAB_TOKEN_TYPE="Job-Token"
ARG GITLAB_TOKEN_VALUE
ARG GITLAB_TOKEN_READ_REPOSITORY
ARG GOPROXY=""
ARG GOPRIVATE

# In order to fetch private modules, use the netrc with the token.
RUN echo "machine gitlab.com login oauth2 password ${GITLAB_TOKEN_READ_REPOSITORY}" > $HOME/.netrc && chmod 600 $HOME/.netrc

COPY project.clj .
RUN gitlab-clojure-deps.sh $GITLAB_TOKEN_TYPE $GITLAB_TOKEN_VALUE

COPY Makefile  *.clj *.iml ./
COPY src/ src/
COPY dev-resources/ dev-resources/
RUN find . | sort && make clean && make all

#-------------------------------------------------------------------------------
# Runtime
#-------------------------------------------------------------------------------
FROM registry.gitlab.com/manetu/tools/unified-builder:v3.0-jre as runtime

ENV JVM_OPTS="-server"

COPY --from=builder /src/target/uberjar/app.j* /usr/local/
COPY docker/entrypoint.sh /usr/local/bin

ENTRYPOINT ["entrypoint.sh"]
