FROM eclipse-temurin:21-alpine as builder

RUN addgroup -g 1000 -S builder
RUN adduser -S -D -G builder -u 1000 builder

USER builder:builder

WORKDIR /home/builder/star2
ADD build.gradle.kts .
ADD gradlew .
ADD settings.gradle.kts .
ADD src/ ./src/
ADD gradle/ ./gradle/

RUN --mount=type=cache,target=/home/builder/.gradle,uid=1000,gid=1000 \
    --mount=type=cache,target=/home/builder/star2/.gradle,uid=1000,gid=1000 \
    --mount=type=cache,target=/home/builder/star2/build,uid=1000,gid=1000 \
    ./gradlew --stacktrace --no-daemon build && cp ./build/libs/*-all.jar out.jar

FROM gcr.io/distroless/java21-debian12:nonroot

WORKDIR /app/
COPY --from=builder --chown=root:root /home/builder/star2/out.jar /app/run.jar

USER nonroot:nonroot
CMD [ "run.jar" ]