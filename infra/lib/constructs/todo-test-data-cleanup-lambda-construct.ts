import * as cdk from 'aws-cdk-lib';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import { Construct } from 'constructs';
import * as path from 'node:path';

export type TodoTestDataCleanupLambdaConstructProps = {
  cluster: rds.IDatabaseCluster;
  databaseSecret: secretsmanager.ISecret;
  databaseName: string;
  batchSize: number;
};

export class TodoTestDataCleanupLambdaConstruct extends Construct {
  public readonly cleanupFunction: lambda.Function;

  constructor(scope: Construct, id: string, props: TodoTestDataCleanupLambdaConstructProps) {
    super(scope, id);

    const cleanupFunctionName = `todo-test-data-cleanup-${cdk.Stack.of(this).stackName}`;

    // なぜ必要か: ログ保持期間と削除ポリシーを制御しつつ、関数ログの保存先を明示するため。
    const cleanupLogGroup = new logs.LogGroup(this, 'TodoTestDataCleanupLogGroup', {
      logGroupName: `/aws/lambda/${cleanupFunctionName}`,
      retention: logs.RetentionDays.ONE_WEEK,
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    // なぜ必要か: 負荷試験後のTodo掃除を手動運用で完結させるため、専用Lambdaを定義する。
    this.cleanupFunction = new lambda.Function(this, 'TodoTestDataCleanupFunction', {
      functionName: cleanupFunctionName,
      runtime: lambda.Runtime.PYTHON_3_13,
      code: lambda.Code.fromAsset(path.join(__dirname, '../../lambda/todo-test-data-cleanup')),
      handler: 'handler.lambda_handler',
      timeout: cdk.Duration.minutes(15),
      memorySize: 512,
      logGroup: cleanupLogGroup,
      environment: {
        DB_CLUSTER_ARN: props.cluster.clusterArn,
        DB_SECRET_ARN: props.databaseSecret.secretArn,
        DB_NAME: props.databaseName,
        BATCH_SIZE: String(props.batchSize),
      },
    });

    // なぜ必要か: Aurora Data API経由でSQLを実行するため、対象クラスターへの最小権限を付与する。
    props.cluster.grantDataApiAccess(this.cleanupFunction);
    // なぜ必要か: Data API実行に必要なDB認証情報をSecrets Managerから参照するため。
    props.databaseSecret.grantRead(this.cleanupFunction);
  }
}
