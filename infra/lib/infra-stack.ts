import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { TodoAlbConstruct } from './constructs/todo-alb-construct';
import { TodoAppSecurityGroupsConstruct } from './constructs/todo-app-security-groups-construct';
import { TodoAuroraConstruct } from './constructs/todo-aurora-construct';
import { TodoBackendEcsServiceConstruct } from './constructs/todo-backend-ecs-service-construct';
import { TodoCloudFrontConstruct } from './constructs/todo-cloudfront-construct';
import { TodoCognitoConstruct } from './constructs/todo-cognito-construct';
import { TodoFrontendDeploymentConstruct } from './constructs/todo-frontend-deployment-construct';
import { TodoFrontendStaticBucketConstruct } from './constructs/todo-frontend-static-bucket-construct';
import { TodoTestDataCleanupLambdaConstruct } from './constructs/todo-test-data-cleanup-lambda-construct';
import { BackendImageDeploymentConstruct } from './constructs/backend-image-deployment-construct';
import { NetworkVpcConstruct } from './constructs/network-vpc-construct';
import { TodoEcrRepositoryConstruct } from './constructs/todo-ecr-repository-construct';

export type InfraStackProps = cdk.StackProps & {
  environmentName: string;
  serviceName: string;
  version: string;
};

export class InfraStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: InfraStackProps) {
    super(scope, id, props);

    // なぜ必要か: ALB/ECS/Auroraで利用する共通ポート/名称を集中管理し、設定不整合を防ぐため。
    const applicationPort = 8080;
    const databasePort = 5432;
    const databaseName = 'todoapp';
    const testDataCleanupBatchSize = 500;
    const healthCheckPath = '/actuator/health';
    const cognitoDomainPrefix = `todo-ecs-sample-${props.environmentName}-auth`;
    const frontendBuildDirectoryPath = path.join(__dirname, '../../frontend/dist');

    // なぜ必要か: S3配備対象を明示し、未ビルド状態で不正な静的配布物をデプロイする事故を防ぐため。
    if (!fs.existsSync(frontendBuildDirectoryPath)) {
      throw new Error(
        `frontend/dist が見つかりません: ${frontendBuildDirectoryPath}. 先に frontend で npm run build を実行してください。`,
      );
    }

    // なぜ必要か: Stack の責務を「構成の組み立て」に限定し、ネットワーク定義を Construct に分離して保守性を高める。
    const networkVpc = new NetworkVpcConstruct(this, 'NetworkVpcConstruct', {
      vpcName: 'NetworkVpc',
    });

    // なぜ必要か: デバッグや後続Stack連携時に対象VPCを一意に参照できるようにする。
    new cdk.CfnOutput(this, 'VpcId', {
      value: networkVpc.vpc.vpcId,
    });

    // なぜ必要か: backendイメージの配布先を固定し、運用時に参照先を統一するため。
    const todoAppEcrRepository = new TodoEcrRepositoryConstruct(this, 'TodoEcrRepositoryConstruct', {
      repositoryName: 'todo',
    });

    // なぜ必要か: backendイメージをビルドし、内容に連動したタグでECRへ配布する責務を分離するため。
    const backendImageDeployment = new BackendImageDeploymentConstruct(this, 'BackendImageDeploymentConstruct', {
      repository: todoAppEcrRepository.repository,
      backendDirectoryPath: path.join(__dirname, '../../backend'),
    });

    // なぜ必要か: ALB/ECS/Aurora間の通信境界を最小権限で管理するため。
    const todoAppSecurityGroups = new TodoAppSecurityGroupsConstruct(this, 'TodoAppSecurityGroupsConstruct', {
      vpc: networkVpc.vpc,
      applicationPort,
      databasePort,
    });

    // なぜ必要か: Todoアプリ永続化基盤としてAurora PostgreSQLとDBシークレットを用意するため。
    const todoAuroraDatabase = new TodoAuroraConstruct(this, 'TodoAuroraConstruct', {
      vpc: networkVpc.vpc,
      securityGroup: todoAppSecurityGroups.auroraSecurityGroup,
      databaseName,
      secretName: `/todo/${props.environmentName}/backend/database`,
    });

    // なぜ必要か: 負荷試験後のTodo掃除を手動実行できる内部バッチを環境ごとに提供するため。
    const todoTestDataCleanupLambda = new TodoTestDataCleanupLambdaConstruct(
      this,
      'TodoTestDataCleanupLambdaConstruct',
      {
        cluster: todoAuroraDatabase.cluster,
        databaseSecret: todoAuroraDatabase.databaseSecret,
        databaseName: todoAuroraDatabase.databaseName,
        batchSize: testDataCleanupBatchSize,
      },
    );

    // なぜ必要か: ECRイメージとDB接続設定を使ってFargateサービスを稼働させるため。
    const todoBackendEcsService = new TodoBackendEcsServiceConstruct(this, 'TodoBackendEcsServiceConstruct', {
      vpc: networkVpc.vpc,
      securityGroup: todoAppSecurityGroups.ecsSecurityGroup,
      repository: todoAppEcrRepository.repository,
      imageTag: backendImageDeployment.imageTag,
      databaseSecret: todoAuroraDatabase.databaseSecret,
      containerPort: applicationPort,
      desiredCount: 2,
    });
    // なぜ必要か: 初回デプロイ時にイメージ未配布のタグをECSがpullして失敗することを防ぐため。
    todoBackendEcsService.service.node.addDependency(backendImageDeployment.imageDeployment);

    // なぜ必要か: インターネット入口としてALBを配置し、ECSサービスへルーティングするため。
    const todoApplicationAlb = new TodoAlbConstruct(this, 'TodoAlbConstruct', {
      vpc: networkVpc.vpc,
      securityGroup: todoAppSecurityGroups.albSecurityGroup,
      service: todoBackendEcsService.service,
      containerPort: applicationPort,
      healthCheckPath,
    });

    // なぜ必要か: SPA成果物をprivateなS3へ配置し、CloudFront default behaviorで配信する土台を作るため。
    const todoFrontendStaticBucket = new TodoFrontendStaticBucketConstruct(this, 'TodoFrontendStaticBucketConstruct');

    // なぜ必要か: CloudFrontを公開入口に配置し、S3静的配信とAPI配信を同一ドメインに統合するため。
    const todoCloudFront = new TodoCloudFrontConstruct(this, 'TodoCloudFrontConstruct', {
      loadBalancer: todoApplicationAlb.loadBalancer,
      staticSiteBucket: todoFrontendStaticBucket.bucket,
    });

    const hostedUiCallbackUrl = `https://${todoCloudFront.distribution.distributionDomainName}/auth/callback`;
    const hostedUiLogoutUrl = `https://${todoCloudFront.distribution.distributionDomainName}/`;

    // なぜ必要か: Hosted UIログインとJWT発行元を管理するCognito認証基盤をアプリ専用に用意するため。
    const todoCognitoAuth = new TodoCognitoConstruct(this, 'TodoCognitoConstruct', {
      callbackUrls: [hostedUiCallbackUrl],
      logoutUrls: [hostedUiLogoutUrl],
      domainPrefix: cognitoDomainPrefix,
    });

    const backendDefaultContainer = todoBackendEcsService.taskDefinition.defaultContainer;
    if (!backendDefaultContainer) {
      throw new Error('TodoBackend のデフォルトコンテナが解決できません。タスク定義を確認してください。');
    }
    // なぜ必要か: backend の JWT issuer 検証先をCognito実体に合わせ、/api 呼び出し時の401ループを防ぐため。
    backendDefaultContainer.addEnvironment(
      'SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI',
      todoCognitoAuth.issuerUrl,
    );

    // なぜ必要か: frontend成果物と実行時設定をS3へ配置し、CloudFrontキャッシュ無効化まで一貫実行するため。
    new TodoFrontendDeploymentConstruct(this, 'TodoFrontendDeploymentConstruct', {
      destinationBucket: todoFrontendStaticBucket.bucket,
      distribution: todoCloudFront.distribution,
      frontendBuildDirectoryPath,
      runtimeConfig: {
        cognitoDomain: todoCognitoAuth.userPoolDomain.baseUrl(),
        cognitoClientId: todoCognitoAuth.userPoolClient.userPoolClientId,
        oauthScopes: ['openid', 'email', 'profile'],
        callbackPath: '/auth/callback',
        logoutPath: '/',
        apiBasePath: '/api',
        persistRefreshToken: false,
      },
    });

    // なぜ必要か: デプロイ後に配布先リポジトリ情報を確認できるようにするため。
    new cdk.CfnOutput(this, 'TodoAppEcrRepositoryName', {
      value: todoAppEcrRepository.repository.repositoryName,
    });

    // なぜ必要か: 他Stackや運用手順から完全修飾URIを参照できるようにするため。
    new cdk.CfnOutput(this, 'TodoAppEcrRepositoryUri', {
      value: todoAppEcrRepository.repository.repositoryUri,
    });

    // なぜ必要か: ECSが参照する実イメージタグをデプロイ成果物として確認できるようにするため。
    new cdk.CfnOutput(this, 'TodoAppEcrImageTag', {
      value: backendImageDeployment.imageTag,
    });

    // なぜ必要か: ALBのアクセス先をデプロイ後に即時確認できるようにするため。
    new cdk.CfnOutput(this, 'TodoAppAlbDnsName', {
      value: todoApplicationAlb.loadBalancer.loadBalancerDnsName,
    });

    // なぜ必要か: API公開先をCloudFrontへ切り替えた後の疎通確認先を明示するため。
    new cdk.CfnOutput(this, 'TodoAppCloudFrontDomainName', {
      value: todoCloudFront.distribution.distributionDomainName,
    });

    // なぜ必要か: 静的配信先バケットを運用確認や障害切り分けで追跡できるようにするため。
    new cdk.CfnOutput(this, 'TodoAppFrontendBucketName', {
      value: todoFrontendStaticBucket.bucket.bucketName,
    });

    // なぜ必要か: backend のJWT検証設定に必要なCognito識別子を運用から参照可能にするため。
    new cdk.CfnOutput(this, 'TodoAppCognitoUserPoolId', {
      value: todoCognitoAuth.userPool.userPoolId,
    });

    // なぜ必要か: frontend のHosted UI設定とバックエンド検証観点でアプリクライアントIDが必要なため。
    new cdk.CfnOutput(this, 'TodoAppCognitoUserPoolClientId', {
      value: todoCognitoAuth.userPoolClient.userPoolClientId,
    });

    // なぜ必要か: Hosted UI ベースURLを明示し、認証導線の確認を容易にするため。
    new cdk.CfnOutput(this, 'TodoAppCognitoHostedUiBaseUrl', {
      value: todoCognitoAuth.userPoolDomain.baseUrl(),
    });

    // なぜ必要か: Resource Server の issuer 検証に使う値をデプロイ成果物として共有するため。
    new cdk.CfnOutput(this, 'TodoAppCognitoIssuerUrl', {
      value: todoCognitoAuth.issuerUrl,
    });

    // なぜ必要か: CloudFront自動生成ドメインを使うcallback/logout URLが適用されたことを運用確認しやすくするため。
    new cdk.CfnOutput(this, 'TodoAppCognitoCallbackUrl', {
      value: hostedUiCallbackUrl,
    });
    new cdk.CfnOutput(this, 'TodoAppCognitoLogoutUrl', {
      value: hostedUiLogoutUrl,
    });

    // なぜ必要か: アプリ接続先のDBエンドポイントを運用確認しやすくするため。
    new cdk.CfnOutput(this, 'TodoAppAuroraEndpointAddress', {
      value: todoAuroraDatabase.cluster.clusterEndpoint.hostname,
    });

    // なぜ必要か: タスク定義のSecret注入先ARNを運用で参照できるようにするため。
    new cdk.CfnOutput(this, 'TodoAppDatabaseSecretArn', {
      value: todoAuroraDatabase.databaseSecret.secretArn,
    });

    // なぜ必要か: 運用者がLambdaコンソールで対象関数を誤認しないよう関数名を出力するため。
    new cdk.CfnOutput(this, 'TodoTestDataCleanupLambdaFunctionName', {
      value: todoTestDataCleanupLambda.cleanupFunction.functionName,
    });

    // なぜ必要か: 全リソースの環境識別・サービス識別・版管理を運用で追跡できるようにする。
    cdk.Tags.of(this).add('env', props.environmentName);
    cdk.Tags.of(this).add('service', props.serviceName);
    cdk.Tags.of(this).add('version', props.version);
  }
}
