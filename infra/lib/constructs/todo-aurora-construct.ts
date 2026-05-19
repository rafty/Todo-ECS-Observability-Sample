import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import { Construct } from 'constructs';

export type TodoAuroraConstructProps = {
  vpc: ec2.IVpc;
  securityGroup: ec2.ISecurityGroup;
  databaseName: string;
  secretName: string;
};

export class TodoAuroraConstruct extends Construct {
  public readonly cluster: rds.DatabaseCluster;
  public readonly databaseSecret: secretsmanager.ISecret;
  public readonly databaseName: string;

  constructor(scope: Construct, id: string, props: TodoAuroraConstructProps) {
    super(scope, id);

    // なぜ必要か: アプリが利用するDB名をConstruct外から参照できるようにして設定重複を防ぐため。
    this.databaseName = props.databaseName;

    // なぜ必要か: Aurora Serverless v2(PostgreSQL)を2AZ前提でdatastoreサブネットへ配置するため。
    this.cluster = new rds.DatabaseCluster(this, 'TodoAuroraCluster', {
      engine: rds.DatabaseClusterEngine.auroraPostgres({
        // なぜ必要か: 16.4 は 2026-05-31 に標準サポート終了予定のため、同一メジャー16系の最新安定版へ更新して計画外の自動更新依存を避ける。
        // なぜ必要か: aws-cdk-lib 2.215.0 には 16.13 の定数がないため、リージョンで提供される有効バージョンを of(...) で明示指定する。
        version: rds.AuroraPostgresEngineVersion.of('16.13', '16'),
      }),
      writer: rds.ClusterInstance.serverlessV2('WriterInstance'),
      serverlessV2MinCapacity: 0.5,
      serverlessV2MaxCapacity: 2,
      // なぜ必要か: 手動起動のテストデータ削除LambdaからVPC非依存でSQL実行できるようにするため。
      enableDataApi: true,
      defaultDatabaseName: props.databaseName,
      credentials: rds.Credentials.fromGeneratedSecret('todoapp', {
        secretName: props.secretName,
      }),
      vpc: props.vpc,
      vpcSubnets: {
        subnetGroupName: 'datastore',
      },
      securityGroups: [props.securityGroup],
      deletionProtection: false,
      removalPolicy: cdk.RemovalPolicy.SNAPSHOT,
    });

    if (!this.cluster.secret) {
      throw new Error('Aurora secret was not generated. Check credential configuration.');
    }

    // なぜ必要か: ECS側に安全に接続情報を渡すため、Auroraが生成したSecret参照を公開する。
    this.databaseSecret = this.cluster.secret;
  }
}
