import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import { Construct } from 'constructs';

export type TodoAlbConstructProps = {
  vpc: ec2.IVpc;
  securityGroup: ec2.ISecurityGroup;
  service: ecs.FargateService;
  containerPort: number;
  healthCheckPath: string;
};

export class TodoAlbConstruct extends Construct {
  public readonly loadBalancer: elbv2.ApplicationLoadBalancer;

  constructor(scope: Construct, id: string, props: TodoAlbConstructProps) {
    super(scope, id);

    // なぜ必要か: インターネット入口をALBに集約し、ECSタスクを直接公開しない構成にするため。
    this.loadBalancer = new elbv2.ApplicationLoadBalancer(this, 'TodoApplicationLoadBalancer', {
      vpc: props.vpc,
      internetFacing: true,
      securityGroup: props.securityGroup,
      vpcSubnets: {
        subnetGroupName: 'front',
      },
    });

    // なぜ必要か: 公開HTTPリクエストをECSターゲットへ受け渡す入口を固定するため。
    const httpListener = this.loadBalancer.addListener('HttpListener', {
      port: 80,
      open: false,
    });

    // なぜ必要か: ヘルスチェックでタスク健全性を判定し、不健全タスクへの転送を避けるため。
    httpListener.addTargets('TodoBackendTargets', {
      port: props.containerPort,
      // なぜ必要か: 3コンテナ構成ではALBの登録先をappコンテナへ明示し、log_router誤登録を防ぐため。
      targets: [
        props.service.loadBalancerTarget({
          containerName: 'TodoBackendContainer',
          containerPort: props.containerPort,
        }),
      ],
      healthCheck: {
        path: props.healthCheckPath,
        healthyHttpCodes: '200-499',
        interval: cdk.Duration.seconds(30),
        timeout: cdk.Duration.seconds(5),
      },
    });
  }
}
