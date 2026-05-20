import * as cdk from 'aws-cdk-lib';
import * as cognito from 'aws-cdk-lib/aws-cognito';
import { Construct } from 'constructs';

export type TodoCognitoConstructProps = {
  callbackUrls: string[];
  logoutUrls: string[];
  domainPrefix: string;
};

export class TodoCognitoConstruct extends Construct {
  public readonly userPool: cognito.UserPool;
  public readonly userPoolClient: cognito.UserPoolClient;
  public readonly userPoolDomain: cognito.UserPoolDomain;
  public readonly issuerUrl: string;

  constructor(scope: Construct, id: string, props: TodoCognitoConstructProps) {
    super(scope, id);

    // なぜ必要か: Todoアプリのユーザー認証基盤をCognitoへ集約し、アプリ側でJWT検証可能な発行元を提供するため。
    this.userPool = new cognito.UserPool(this, 'TodoUserPool', {
      userPoolName: 'todo-user-pool',
      selfSignUpEnabled: true,
      signInAliases: {
        email: true,
      },
      autoVerify: {
        email: true,
      },
      mfa: cognito.Mfa.OFF,
      // なぜ必要か: サンプル要件に合わせて、複雑度を上げすぎない簡易ポリシーで登録可能にするため。
      passwordPolicy: {
        minLength: 8,
        requireLowercase: false,
        requireUppercase: false,
        requireDigits: false,
        requireSymbols: false,
        tempPasswordValidity: cdk.Duration.days(7),
      },
      accountRecovery: cognito.AccountRecovery.EMAIL_ONLY,
    });

    // なぜ必要か: React SPA からHosted UIのAuthorization Code + PKCEを利用する公開クライアントを用意するため。
    this.userPoolClient = this.userPool.addClient('TodoAppClient', {
      userPoolClientName: 'todo-app-client',
      generateSecret: false,
      authFlows: {
        // なぜ必要か: 負荷試験用JWT生成でAdminInitiateAuth(ADMIN_USER_PASSWORD_AUTH)を利用可能にするため。
        adminUserPassword: true,
        userSrp: true,
      },
      // なぜ必要か: DLT負荷試験の実行中にAccess Token/ID Tokenが短時間で期限切れにならないようにするため。
      accessTokenValidity: cdk.Duration.hours(12),
      // なぜ必要か: Access Tokenと同一の有効期限にそろえ、検証時のトークン管理を単純化するため。
      idTokenValidity: cdk.Duration.hours(12),
      oAuth: {
        flows: {
          authorizationCodeGrant: true,
        },
        scopes: [cognito.OAuthScope.OPENID, cognito.OAuthScope.EMAIL, cognito.OAuthScope.PROFILE],
        callbackUrls: props.callbackUrls,
        logoutUrls: props.logoutUrls,
      },
      supportedIdentityProviders: [cognito.UserPoolClientIdentityProvider.COGNITO],
      preventUserExistenceErrors: true,
      // なぜ必要か: refresh tokenの使い回し期間を短縮し、盗難トークンの悪用余地を減らすため。
      refreshTokenRotationGracePeriod: cdk.Duration.seconds(10),
    });

    // なぜ必要か: Hosted UIのエンドポイントを払い出し、フロントエンドログイン導線を成立させるため。
    this.userPoolDomain = this.userPool.addDomain('TodoUserPoolDomain', {
      cognitoDomain: {
        domainPrefix: props.domainPrefix,
      },
      managedLoginVersion: cognito.ManagedLoginVersion.CLASSIC_HOSTED_UI,
    });

    // なぜ必要か: backend の Resource Server 検証で `iss` を固定し、不正トークン受け入れを防ぐため。
    this.issuerUrl = `https://cognito-idp.${cdk.Stack.of(this).region}.amazonaws.com/${this.userPool.userPoolId}`;
  }
}
