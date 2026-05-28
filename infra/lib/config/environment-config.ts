export type EnvironmentName = 'dev' | 'stg' | 'prod';

export type DatadogSidecarResourceConfig = {
  cpu: number;
  memoryLimitMiB: number;
  memoryReservationMiB: number;
};

export type DatadogConfig = {
  ddSite: string;
  ddService: string;
  ddTags: string;
  apiKeySecretName: string;
  firelensLogHost: string;
  firelensConfigFileType?: 'file' | 's3';
  firelensConfigFileValue?: string;
  datadogAgent: DatadogSidecarResourceConfig;
  logRouter: DatadogSidecarResourceConfig;
  datadogAgentLogRetentionDays: number;
  logRouterLogRetentionDays: number;
  apmMaxTps: number;
  apmErrorTps: number;
};

export type EnvironmentConfig = {
  environmentName: EnvironmentName;
  accountId: string;
  region: string;
  datadog: DatadogConfig;
};

const ddService = 'todo-backend';
const ddSite = 'datadoghq.com';

// なぜ必要か: Datadog Logs の送信先を DD_SITE に合わせて固定し、誤設定でのログ欠損を防ぐため。
const firelensLogHostBySite: Record<string, string> = {
  'datadoghq.com': 'http-intake.logs.datadoghq.com',
};

function buildDatadogConfig(environmentName: EnvironmentName, accountId: string): DatadogConfig {
  const firelensLogHost = firelensLogHostBySite[ddSite];
  if (!firelensLogHost) {
    throw new Error(`未対応の DD_SITE です: ${ddSite}`);
  }

  return {
    ddSite,
    ddService,
    // なぜ必要か: Unified Service Tagging の拡張タグを環境ごとに統一し、service/env/version の重複定義を避けるため。
    ddTags: `team:o11y-CoE,system:todo,aws_account:${accountId}`,
    // なぜ必要か: 環境とサービスで一意な Secret 命名規約をコード化し、運用手順との不整合を防ぐため。
    apiKeySecretName: `/${environmentName}/${ddService}/datadog/api-key`,
    firelensLogHost,
    // なぜ必要か: 既定の FireLens 生成設定を利用し、存在しない設定ファイル参照で起動失敗する事故を防ぐため。
    // 注意: カスタム設定を使う場合のみ firelensConfigFileType / firelensConfigFileValue を環境ごとに明示すること。
    datadogAgent: {
      cpu: 256,
      memoryLimitMiB: 512,
      memoryReservationMiB: 256,
    },
    logRouter: {
      cpu: 64,
      memoryLimitMiB: 128,
      memoryReservationMiB: 64,
    },
    datadogAgentLogRetentionDays: environmentName === 'dev' ? 3 : environmentName === 'stg' ? 7 : 14,
    logRouterLogRetentionDays: environmentName === 'dev' ? 3 : environmentName === 'stg' ? 7 : 14,
    apmMaxTps: 2,
    apmErrorTps: 10,
  };
}

const environmentConfigs: Record<EnvironmentName, EnvironmentConfig> = {
  // なぜ必要か: 将来の環境追加時に同じ構成で横展開できるよう、環境設定を明示しておく。
  dev: {
    environmentName: 'dev',
    accountId: '111111111111',
    region: 'ap-northeast-1',
    datadog: buildDatadogConfig('dev', '111111111111'),
  },
  stg: {
    environmentName: 'stg',
    accountId: '222222222222',
    region: 'ap-northeast-1',
    datadog: buildDatadogConfig('stg', '222222222222'),
  },
  // なぜ必要か: 本featureで実際に対象とする本番環境のデプロイ先を固定する。
  prod: {
    environmentName: 'prod',
    accountId: '288742313204',
    region: 'ap-northeast-1',
    datadog: buildDatadogConfig('prod', '288742313204'),
  },
};

export function getEnvironmentConfig(targetEnvironment: string): EnvironmentConfig {
  if (targetEnvironment in environmentConfigs) {
    return environmentConfigs[targetEnvironment as EnvironmentName];
  }

  // なぜ必要か: 不正な環境名で意図しないアカウントにデプロイされる事故を防止する。
  throw new Error(`未対応の環境指定です: ${targetEnvironment}. 利用可能: dev, stg, prod`);
}
