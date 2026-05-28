#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import { InfraStack } from '../lib/infra-stack';
import { getEnvironmentConfig } from '../lib/config/environment-config';

const app = new cdk.App();

// なぜ必要か: 環境誤指定による誤デプロイを防ぐため、明示的に context の env を必須化する。
const targetEnvironment = app.node.tryGetContext('env');

if (typeof targetEnvironment !== 'string' || targetEnvironment.trim().length === 0) {
  throw new Error('環境指定が未設定です。`cdk synth -c env=prod` のように `-c env=<dev|stg|prod>` を指定してください。');
}

// なぜ必要か: 環境ごとの account/region をコード分岐ではなく設定ファイルに集約し、保守性を高める。
const environmentConfig = getEnvironmentConfig(targetEnvironment);

new InfraStack(app, `InfraStack-${environmentConfig.environmentName}`, {
  env: {
    account: environmentConfig.accountId,
    region: environmentConfig.region,
  },
  environmentName: environmentConfig.environmentName,
  serviceName: 'Todo',
  version: '1.00',
  datadogConfig: environmentConfig.datadog,
});
