import * as cdk from 'aws-cdk-lib';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { InfraStack } from '../lib/infra-stack';

test('Network, ECS, ALB, CloudFront, Cognito and Aurora resources are defined', () => {
  const app = new cdk.App();
  const stack = new InfraStack(app, 'MyTestStack', {
    env: { account: '111111111111', region: 'ap-northeast-1' },
    environmentName: 'prod',
    serviceName: 'Todo',
    version: '1.00',
  });

  const template = Template.fromStack(stack);

  template.resourceCountIs('AWS::EC2::VPC', 1);
  template.resourceCountIs('AWS::EC2::Subnet', 6);
  template.resourceCountIs('AWS::EC2::NatGateway', 1);
  template.resourceCountIs('AWS::ECR::Repository', 1);
  template.resourceCountIs('AWS::S3::Bucket', 1);
  template.resourceCountIs('AWS::ECS::Cluster', 1);
  template.resourceCountIs('AWS::ECS::Service', 1);
  template.resourceCountIs('AWS::ECS::TaskDefinition', 1);
  template.resourceCountIs('AWS::ElasticLoadBalancingV2::LoadBalancer', 1);
  template.resourceCountIs('AWS::ElasticLoadBalancingV2::TargetGroup', 1);
  template.resourceCountIs('AWS::CloudFront::Distribution', 1);
  template.resourceCountIs('Custom::CDKBucketDeployment', 1);
  template.resourceCountIs('Custom::CDKECRDeployment', 1);
  template.resourceCountIs('AWS::CodeBuild::Project', 0);
  template.resourceCountIs('AWS::Events::Rule', 0);
  template.resourceCountIs('AWS::Cognito::UserPool', 1);
  template.resourceCountIs('AWS::Cognito::UserPoolClient', 1);
  template.resourceCountIs('AWS::Cognito::UserPoolDomain', 1);
  template.resourceCountIs('AWS::RDS::DBCluster', 1);
  template.resourceCountIs('AWS::RDS::DBInstance', 1);
  template.resourceCountIs('AWS::SecretsManager::Secret', 1);

  // なぜ必要か: ECRリポジトリ名と削除ポリシーが仕様どおりであることを担保するため。
  template.hasResourceProperties('AWS::ECR::Repository', {
    RepositoryName: 'todo',
  });
  template.hasResource('AWS::ECR::Repository', {
    DeletionPolicy: 'Delete',
    UpdateReplacePolicy: 'Delete',
  });

  // なぜ必要か: frontend配信バケットが非公開構成で作成されることを担保するため。
  template.hasResourceProperties('AWS::S3::Bucket', {
    PublicAccessBlockConfiguration: Match.objectLike({
      BlockPublicAcls: true,
      BlockPublicPolicy: true,
      IgnorePublicAcls: true,
      RestrictPublicBuckets: true,
    }),
  });

  // なぜ必要か: ECSがECRの可変タグを参照し、DB接続情報をシークレット経由で受け取ることを担保するため。
  template.hasResourceProperties('AWS::ECS::TaskDefinition', {
    ContainerDefinitions: Match.arrayWith([
      Match.objectLike({
        Image: Match.anyValue(),
        PortMappings: Match.arrayWith([
          Match.objectLike({
            ContainerPort: 8080,
          }),
        ]),
        Secrets: Match.arrayWith([
          Match.objectLike({
            Name: 'SPRING_DATASOURCE_USERNAME',
          }),
          Match.objectLike({
            Name: 'SPRING_DATASOURCE_PASSWORD',
          }),
        ]),
      }),
    ]),
  });
  expect(JSON.stringify(template.toJSON())).not.toContain(':latest');

  // なぜ必要か: 手動実行のTodo掃除LambdaがPython 3.13で定義され、入力制御用環境変数を持つことを担保するため。
  template.hasResourceProperties('AWS::Lambda::Function', {
    Runtime: 'python3.13',
    Handler: 'handler.lambda_handler',
    Timeout: 900,
    MemorySize: 512,
    Environment: Match.objectLike({
      Variables: Match.objectLike({
        DB_NAME: 'todoapp',
        BATCH_SIZE: '500',
      }),
    }),
  });

  // なぜ必要か: cleanup Lambda の実行ロールに Data API 実行権限と Secret 読み取り権限が付与されることを担保するため。
  expect(JSON.stringify(template.toJSON())).toContain('rds-data:ExecuteStatement');
  expect(JSON.stringify(template.toJSON())).toContain('secretsmanager:GetSecretValue');

  // なぜ必要か: AuroraがPostgreSQLエンジンで作成され、todos用途のDB名を保持することを担保するため。
  template.hasResourceProperties('AWS::RDS::DBCluster', {
    Engine: 'aurora-postgresql',
    EngineVersion: '16.13',
    DatabaseName: 'todoapp',
    EnableHttpEndpoint: true,
  });

  // なぜ必要か: ALB経由のヘルスチェックパスが `/actuator/health` に統一されることを担保するため。
  template.hasResourceProperties('AWS::ElasticLoadBalancingV2::TargetGroup', {
    Port: 8080,
    Protocol: 'HTTP',
    HealthCheckPath: '/actuator/health',
  });

  // なぜ必要か: ALB受信元がCloudFront managed prefix listに限定され、直アクセス抑止が有効であることを担保するため。
  template.hasResourceProperties('AWS::EC2::SecurityGroupIngress', {
    FromPort: 80,
    ToPort: 80,
    IpProtocol: 'tcp',
    SourcePrefixListId: Match.anyValue(),
  });

  // なぜ必要か: 最小通信ルールとしてALB -> ECS -> Aurora の導線が形成されることを担保するため。
  template.hasResourceProperties('AWS::EC2::SecurityGroupIngress', {
    FromPort: 8080,
    ToPort: 8080,
    IpProtocol: 'tcp',
  });
  template.hasResourceProperties('AWS::EC2::SecurityGroupIngress', {
    FromPort: 5432,
    ToPort: 5432,
    IpProtocol: 'tcp',
  });
  template.hasResourceProperties('AWS::EC2::SecurityGroupEgress', {
    FromPort: 5432,
    ToPort: 5432,
    IpProtocol: 'tcp',
  });

  // なぜ必要か: CloudFrontの公開要件（HTTPS強制、全メソッド許可、no-cache、Authorization転送）が満たされることを担保するため。
  template.hasResourceProperties('AWS::CloudFront::Distribution', {
    DistributionConfig: Match.objectLike({
      DefaultCacheBehavior: Match.objectLike({
        ViewerProtocolPolicy: 'redirect-to-https',
        AllowedMethods: Match.arrayWith(['GET', 'HEAD', 'OPTIONS']),
        CachePolicyId: Match.anyValue(),
      }),
      DefaultRootObject: 'index.html',
      CustomErrorResponses: Match.arrayWith([
        Match.objectLike({
          ErrorCode: 403,
          ResponseCode: 200,
          ResponsePagePath: '/index.html',
        }),
        Match.objectLike({
          ErrorCode: 404,
          ResponseCode: 200,
          ResponsePagePath: '/index.html',
        }),
      ]),
      CacheBehaviors: Match.arrayWith([
        Match.objectLike({
          PathPattern: '/api/*',
          AllowedMethods: Match.arrayWith(['GET', 'HEAD', 'OPTIONS', 'PUT', 'PATCH', 'POST', 'DELETE']),
          CachePolicyId: Match.anyValue(),
          OriginRequestPolicyId: Match.anyValue(),
        }),
      ]),
    }),
  });

  // なぜ必要か: BucketDeploymentがCloudFront無効化( /* )まで一貫実行されることを担保するため。
  template.hasResourceProperties('Custom::CDKBucketDeployment', {
    DistributionId: Match.anyValue(),
    DistributionPaths: ['/*'],
  });

  // なぜ必要か: Cognito App Client が公開クライアントとして code flow とCloudFront連携URLを持つことを担保するため。
  template.hasResourceProperties('AWS::Cognito::UserPoolClient', {
    GenerateSecret: false,
    AllowedOAuthFlows: ['code'],
    AllowedOAuthFlowsUserPoolClient: true,
    CallbackURLs: Match.anyValue(),
    LogoutURLs: Match.anyValue(),
    RefreshTokenRotation: Match.objectLike({
      Feature: 'ENABLED',
      RetryGracePeriodSeconds: 10,
    }),
  });

  // なぜ必要か: User Pool がサンプル要件（自己登録可、MFA不要、簡易パスワード）を満たすことを担保するため。
  template.hasResourceProperties('AWS::Cognito::UserPool', {
    MfaConfiguration: 'OFF',
    AdminCreateUserConfig: Match.objectLike({
      AllowAdminCreateUserOnly: false,
    }),
    Policies: Match.objectLike({
      PasswordPolicy: Match.objectLike({
        MinimumLength: 8,
        RequireLowercase: false,
        RequireUppercase: false,
        RequireNumbers: false,
        RequireSymbols: false,
        TemporaryPasswordValidityDays: 7,
      }),
    }),
  });

  // なぜ必要か: callback/logout動的連携がStack出力に明示され、運用確認で検証しやすくなるため。
  template.hasOutput('TodoAppCognitoCallbackUrl', {
    Value: Match.anyValue(),
  });
  template.hasOutput('TodoAppCognitoLogoutUrl', {
    Value: Match.anyValue(),
  });

  // なぜ必要か: callback/logoutがCloudFrontドメイン参照であることをテンプレート文字列上で確認するため。
  expect(JSON.stringify(template.toJSON())).toContain('/auth/callback');
  expect(JSON.stringify(template.toJSON())).toContain('"DomainName"');

  template.hasResourceProperties('AWS::EC2::VPC', {
    Tags: Match.arrayWith([
      { Key: 'env', Value: 'prod' },
      { Key: 'service', Value: 'Todo' },
      { Key: 'version', Value: '1.00' },
    ]),
  });
});
