import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import { Construct } from 'constructs';
import { DatadogConfig } from '../config/environment-config';

export type TodoBackendEcsServiceConstructProps = {
  vpc: ec2.IVpc;
  securityGroup: ec2.ISecurityGroup;
  repository: ecr.IRepository;
  imageTag: string;
  databaseSecret: secretsmanager.ISecret;
  datadogApiKeySecret: secretsmanager.ISecret;
  datadogConfig: DatadogConfig;
  environmentName: string;
  containerPort: number;
  desiredCount: number;
};

export class TodoBackendEcsServiceConstruct extends Construct {
  public readonly cluster: ecs.Cluster;
  public readonly taskDefinition: ecs.FargateTaskDefinition;
  public readonly service: ecs.FargateService;

  constructor(scope: Construct, id: string, props: TodoBackendEcsServiceConstructProps) {
    super(scope, id);

    // なぜ必要か: Todoバックエンドの実行先を既存VPC内で管理するECSクラスターを作るため。
    this.cluster = new ecs.Cluster(this, 'TodoBackendCluster', {
      vpc: props.vpc,
      clusterName: 'todo-backend-cluster',
    });

    // なぜ必要か: sidecar 分を含むタスクCPU/メモリを確保し、Fargate の有効な組み合わせ制約を満たすため。
    this.taskDefinition = new ecs.FargateTaskDefinition(this, 'TodoBackendTaskDefinition', {
      cpu: 1024,
      memoryLimitMiB: 2048,
    });

    const datadogAgentLogRetention = props.datadogConfig.datadogAgentLogRetentionDays === 3
      ? logs.RetentionDays.THREE_DAYS
      : props.datadogConfig.datadogAgentLogRetentionDays === 7
      ? logs.RetentionDays.ONE_WEEK
      : logs.RetentionDays.TWO_WEEKS;
    const logRouterLogRetention = props.datadogConfig.logRouterLogRetentionDays === 3
      ? logs.RetentionDays.THREE_DAYS
      : props.datadogConfig.logRouterLogRetentionDays === 7
      ? logs.RetentionDays.ONE_WEEK
      : logs.RetentionDays.TWO_WEEKS;

    // なぜ必要か: datadog-agent の診断ログを環境別保持日数で保存し、障害時の切り分けを可能にするため。
    const datadogAgentLogGroup = new logs.LogGroup(this, 'DatadogAgentLogGroup', {
      retention: datadogAgentLogRetention,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    // なぜ必要か: log_router の診断ログを環境別保持日数で保存し、ログ転送不具合を追跡可能にするため。
    const logRouterLogGroup = new logs.LogGroup(this, 'LogRouterLogGroup', {
      retention: logRouterLogRetention,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    const firelensOptions: ecs.FirelensOptions =
      props.datadogConfig.firelensConfigFileType && props.datadogConfig.firelensConfigFileValue
        ? {
            enableECSLogMetadata: true,
            configFileType:
              props.datadogConfig.firelensConfigFileType === 's3'
                ? ecs.FirelensConfigFileType.S3
                : ecs.FirelensConfigFileType.FILE,
            configFileValue: props.datadogConfig.firelensConfigFileValue,
          }
        : {
            enableECSLogMetadata: true,
          };

    // なぜ必要か: FireLens で Datadog 出力プラグインを有効化し、アプリログを Datadog Logs へ転送するため。
    this.taskDefinition.addFirelensLogRouter('LogRouterContainer', {
      image: ecs.ContainerImage.fromRegistry('public.ecr.aws/aws-observability/aws-for-fluent-bit:stable'),
      cpu: props.datadogConfig.logRouter.cpu,
      memoryReservationMiB: props.datadogConfig.logRouter.memoryReservationMiB,
      memoryLimitMiB: props.datadogConfig.logRouter.memoryLimitMiB,
      // なぜ必要か: CDK synth 時のコンテナ検証でポート未定義エラーを回避し、FireLens 標準受け口を明示するため。
      portMappings: [
        {
          containerPort: 24224,
          protocol: ecs.Protocol.TCP,
        },
      ],
      firelensConfig: {
        type: ecs.FirelensLogRouterType.FLUENTBIT,
        options: firelensOptions,
      },
      logging: ecs.LogDrivers.awsLogs({
        logGroup: logRouterLogGroup,
        streamPrefix: `${props.environmentName}-log-router`,
      }),
    });

    if (props.datadogConfig.firelensConfigFileType === 's3' && props.datadogConfig.firelensConfigFileValue) {
      // なぜ必要か: S3 から Fluent Bit 設定ファイルを読み込む場合の最小権限をタスクロールへ付与するため。
      const configBucketArn = props.datadogConfig.firelensConfigFileValue.replace(/\/[^/]+$/, '');
      this.taskDefinition.taskRole.addToPrincipalPolicy(
        new iam.PolicyStatement({
          actions: ['s3:GetObject'],
          resources: [props.datadogConfig.firelensConfigFileValue, `${configBucketArn}/*`],
        }),
      );
    }

    // なぜ必要か: app とは別コンテナで Datadog Agent を動かし、OTLP gRPC の受け口を提供するため。
    this.taskDefinition.addContainer('DatadogAgentContainer', {
      image: ecs.ContainerImage.fromRegistry(props.datadogConfig.datadogAgentImage),
      cpu: props.datadogConfig.datadogAgent.cpu,
      memoryReservationMiB: props.datadogConfig.datadogAgent.memoryReservationMiB,
      memoryLimitMiB: props.datadogConfig.datadogAgent.memoryLimitMiB,
      portMappings: [
        {
          containerPort: 4317,
          protocol: ecs.Protocol.TCP,
        },
        // なぜ必要か: OTLP/HTTP で metrics を受ける 4318 を同一タスク内通信で利用できるよう明示するため。
        {
          containerPort: 4318,
          protocol: ecs.Protocol.TCP,
        },
      ],
      logging: ecs.LogDrivers.awsLogs({
        logGroup: datadogAgentLogGroup,
        streamPrefix: `${props.environmentName}-datadog-agent`,
      }),
      environment: {
        ECS_FARGATE: 'true',
        DD_APM_ENABLED: 'true',
        DD_APM_NON_LOCAL_TRAFFIC: 'true',
        // なぜ必要か: health check由来traceをAPM ingest対象外にし、業務APIのtrace可読性とコストを守るため。
        DD_APM_IGNORE_RESOURCES: props.datadogConfig.apmIgnoreResources,
        // なぜ必要か: ECS/Fargate で task_arn などオーケストレーター粒度タグを付与し、Datadog 上の絞り込みを可能にするため。
        DD_CHECKS_TAG_CARDINALITY: 'orchestrator',
        DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_GRPC_ENDPOINT: '0.0.0.0:4317',
        // なぜ必要か: Micrometer OTLP metrics は HTTP 送信のため、Agent 側に OTLP/HTTP 受け口(4318)を有効化するため。
        DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_HTTP_ENDPOINT: '0.0.0.0:4318',
        DD_SITE: props.datadogConfig.ddSite,
        DD_ENV: props.environmentName,
        DD_SERVICE: props.datadogConfig.ddService,
        DD_VERSION: props.imageTag,
        DD_TAGS: props.datadogConfig.ddTags,
        DD_APM_MAX_TPS: String(props.datadogConfig.apmMaxTps),
        DD_APM_ERROR_TPS: String(props.datadogConfig.apmErrorTps),
      },
      secrets: {
        DD_API_KEY: ecs.Secret.fromSecretsManager(props.datadogApiKeySecret),
      },
    });

    // なぜ必要か: ECRへ配布された指定タグのSpring BootイメージをFargateで実行するため。
    this.taskDefinition.addContainer('TodoBackendContainer', {
      image: ecs.ContainerImage.fromEcrRepository(props.repository, props.imageTag),
      logging: ecs.LogDrivers.firelens({
        options: {
          Name: 'datadog',
          Host: props.datadogConfig.firelensLogHost,
          TLS: 'on',
          compress: 'gzip',
          provider: 'ecs',
          dd_service: props.datadogConfig.ddService,
          dd_source: 'java',
          dd_tags: `env:${props.environmentName},version:${props.imageTag},${props.datadogConfig.ddTags}`,
          dd_message_key: 'log',
        },
        secretOptions: {
          apikey: ecs.Secret.fromSecretsManager(props.datadogApiKeySecret),
        },
      }),
      portMappings: [
        {
          containerPort: props.containerPort,
          protocol: ecs.Protocol.TCP,
        },
      ],
      environment: {
        // なぜ必要か: 認証導入前でもowner_subject入力方針を段階的に検証できるよう既定値を保持するため。
        TODO_OWNER_SUBJECT_DEFAULT: 'anonymous',
        // なぜ必要か: OpenTelemetry Java AgentをJVM起動時にattachし、Spring/JDBC/Runtimeを自動計装するため。
        JAVA_TOOL_OPTIONS: '-javaagent:/app/opentelemetry-javaagent.jar',
        OTEL_TRACES_EXPORTER: 'otlp',
        // なぜ必要か: traceは同一タスク内Datadog AgentのOTLP/gRPC受け口(4317)へ送信するため。
        OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: 'http://localhost:4317',
        // なぜ必要か: Java Agent 2.xの既定protocol差分に依存せず、trace経路をgRPCへ固定するため。
        OTEL_EXPORTER_OTLP_TRACES_PROTOCOL: 'grpc',
        OTEL_METRICS_EXPORTER: 'otlp',
        // なぜ必要か: metricsは同一タスク内Datadog AgentのOTLP/HTTP受け口(4318)へ送信するため。
        OTEL_EXPORTER_OTLP_METRICS_ENDPOINT: 'http://localhost:4318/v1/metrics',
        // なぜ必要か: Datadog AgentのOTLP/HTTP metrics endpointへ正しいprotocolで送るため。
        OTEL_EXPORTER_OTLP_METRICS_PROTOCOL: 'http/protobuf',
        // なぜ必要か: DatadogのOTLP metrics推奨に合わせ、counter系metricsの解釈を安定させるため。
        OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE: 'delta',
        OTEL_LOGS_EXPORTER: 'none',
        // なぜ必要か: JDBC metricsを安定化したdatabase semantic conventionsで出力するため。
        OTEL_SEMCONV_STABILITY_OPT_IN: 'database',
        // なぜ必要か: 既存Micrometer業務metricsをJava Agent経由でDatadog Metricsへ送るため。
        OTEL_INSTRUMENTATION_MICROMETER_ENABLED: 'true',
        // なぜ必要か: Java Agent由来のJVM Runtime Metricsを明示的に有効化し、GC/CPU/ThreadをDatadogで確認するため。
        OTEL_INSTRUMENTATION_RUNTIME_TELEMETRY_ENABLED: 'true',
        // なぜ必要か: SQL bind parameter等の機密値がspan属性へ出る事故を防ぐため。
        OTEL_INSTRUMENTATION_COMMON_DB_STATEMENT_SANITIZER_ENABLED: 'true',
        OTEL_SERVICE_NAME: props.datadogConfig.ddService,
        OTEL_RESOURCE_ATTRIBUTES: `service.name=${props.datadogConfig.ddService},service.version=${props.imageTag},deployment.environment=${props.environmentName}`,
        DD_SERVICE: props.datadogConfig.ddService,
        DD_ENV: props.environmentName,
        DD_VERSION: props.imageTag,
      },
      secrets: {
        // なぜ必要か: DB接続情報を平文環境変数に置かずSecrets Manager経由で渡すため。
        SPRING_DATASOURCE_HOST: ecs.Secret.fromSecretsManager(props.databaseSecret, 'host'),
        SPRING_DATASOURCE_PORT: ecs.Secret.fromSecretsManager(props.databaseSecret, 'port'),
        SPRING_DATASOURCE_DBNAME: ecs.Secret.fromSecretsManager(props.databaseSecret, 'dbname'),
        SPRING_DATASOURCE_USERNAME: ecs.Secret.fromSecretsManager(props.databaseSecret, 'username'),
        SPRING_DATASOURCE_PASSWORD: ecs.Secret.fromSecretsManager(props.databaseSecret, 'password'),
      },
    });

    // なぜ必要か: コンテナ起動時のイメージpullに必要な権限を実行ロールへ限定付与するため。
    if (this.taskDefinition.executionRole) {
      props.repository.grantPull(this.taskDefinition.executionRole);
      props.databaseSecret.grantRead(this.taskDefinition.executionRole);
      props.datadogApiKeySecret.grantRead(this.taskDefinition.executionRole);
    }

    // なぜ必要か: アプリ実行時にDBシークレット参照が必要なため、タスクロールにも最小権限を付与する。
    props.databaseSecret.grantRead(this.taskDefinition.taskRole);
    props.datadogApiKeySecret.grantRead(this.taskDefinition.taskRole);

    // なぜ必要か: ALB配下で稼働する常駐APIとしてFargateサービスをapplicationサブネットに配置するため。
    this.service = new ecs.FargateService(this, 'TodoBackendService', {
      cluster: this.cluster,
      taskDefinition: this.taskDefinition,
      desiredCount: props.desiredCount,
      assignPublicIp: false,
      securityGroups: [props.securityGroup],
      vpcSubnets: {
        subnetGroupName: 'application',
      },
    });
  }
}
