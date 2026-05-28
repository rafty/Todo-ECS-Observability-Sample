# syntax=docker/dockerfile:1

# なぜ必要か: Javaアプリのビルドをコンテナ内で完結させ、ローカル環境差分を排除するため。
FROM maven:3.9.9-amazoncorretto-21 AS build
WORKDIR /workspace

# なぜ必要か: 依存解決をソースコードコピーより先に実行し、Dockerレイヤーキャッシュを効かせるため。
COPY pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline

# なぜ必要か: アプリ本体をコピーして実行可能Jarを作成するため。
COPY src/ src/
RUN mvn -B -DskipTests package

# なぜ必要か: 実行環境をAmazon Correttoに固定し、JDKとOS差分を減らすため。
FROM amazoncorretto:21-alpine3.20
WORKDIR /app

# なぜ必要か: 要件に合わせてコンテナのタイムゾーンをJSTに統一するため。
ENV TZ=Asia/Tokyo
RUN apk add --no-cache tzdata \
    && cp /usr/share/zoneinfo/Asia/Tokyo /etc/localtime \
    && echo "Asia/Tokyo" > /etc/timezone \
    && apk del tzdata

# なぜ必要か: コンテナ内でroot権限を使わず実行し、侵害時の影響範囲を最小化するため。
RUN addgroup -S spring \
    && adduser -S -G spring spring

# なぜ必要か: ビルド済みJarのみを実行イメージに含め、不要ファイルを持ち込まないため。
COPY --from=build --chown=spring:spring /workspace/target/*.jar /app/app.jar

# なぜ必要か: アプリ実行ユーザーを固定し、以降のプロセスを非rootで起動するため。
USER spring:spring

# なぜ必要か: アプリのデフォルト待受ポートを明示し、利用者と実行基盤の設定ミスを減らすため。
EXPOSE 8080

# なぜ必要か: Spring Bootアプリを単一プロセスで起動するため。
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
