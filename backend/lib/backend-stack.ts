import * as path from 'path';
import * as cdk from 'aws-cdk-lib/core';
import { Construct } from 'constructs';
import { NodejsFunction } from 'aws-cdk-lib/aws-lambda-nodejs';
import { Runtime } from 'aws-cdk-lib/aws-lambda';
import { HttpApi, HttpMethod } from 'aws-cdk-lib/aws-apigatewayv2';
import { HttpLambdaIntegration } from 'aws-cdk-lib/aws-apigatewayv2-integrations';

export class BackendStack extends cdk.Stack {
	constructor(scope: Construct, id: string, props?: cdk.StackProps) {
		super(scope, id, props);

		// Phase 3 step 1: prove the whole deploy pipeline (CDK, Lambda,
		// HTTP API, IAM) works end to end with a trivial endpoint before
		// building anything Minecraft-specific on top of it.
		const healthFn = new NodejsFunction(this, 'HealthFunction', {
			entry: path.join(__dirname, '..', 'lambda', 'health.ts'),
			runtime: Runtime.NODEJS_24_X,
			handler: 'handler',
		});

		const api = new HttpApi(this, 'HttpApi');
		api.addRoutes({
			path: '/health',
			integration: new HttpLambdaIntegration('HealthIntegration', healthFn),
		});

		const verifySessionFn = new NodejsFunction(this, 'VerifySessionFunction', {
			entry: path.join(__dirname, '..', 'lambda', 'verifySession.ts'),
			runtime: Runtime.NODEJS_24_X,
			handler: 'handler',
		});
		api.addRoutes({
			path: '/auth/verify',
			methods: [HttpMethod.POST],
			integration: new HttpLambdaIntegration('VerifySessionIntegration', verifySessionFn),
		});

		new cdk.CfnOutput(this, 'ApiUrl', { value: api.apiEndpoint });
	}
}
