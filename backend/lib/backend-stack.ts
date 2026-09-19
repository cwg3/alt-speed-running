import * as path from 'path';
import * as cdk from 'aws-cdk-lib/core';
import { Construct } from 'constructs';
import { NodejsFunction } from 'aws-cdk-lib/aws-lambda-nodejs';
import { Runtime } from 'aws-cdk-lib/aws-lambda';
import { HttpApi, HttpMethod } from 'aws-cdk-lib/aws-apigatewayv2';
import { HttpLambdaIntegration } from 'aws-cdk-lib/aws-apigatewayv2-integrations';
import { AttributeType, BillingMode, Table } from 'aws-cdk-lib/aws-dynamodb';

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

		// Pay-per-request billing: no capacity to plan/pay for while this is
		// just us testing - cost tracks actual usage, same reasoning as the
		// serverless-first choice for the whole backend.
		const playersTable = new Table(this, 'PlayersTable', {
			partitionKey: { name: 'uuid', type: AttributeType.STRING },
			billingMode: BillingMode.PAY_PER_REQUEST,
		});

		const sessionsTable = new Table(this, 'SessionsTable', {
			partitionKey: { name: 'token', type: AttributeType.STRING },
			billingMode: BillingMode.PAY_PER_REQUEST,
			timeToLiveAttribute: 'expiresAt',
		});

		const verifySessionFn = new NodejsFunction(this, 'VerifySessionFunction', {
			entry: path.join(__dirname, '..', 'lambda', 'verifySession.ts'),
			runtime: Runtime.NODEJS_24_X,
			handler: 'handler',
			environment: {
				PLAYERS_TABLE_NAME: playersTable.tableName,
				SESSIONS_TABLE_NAME: sessionsTable.tableName,
			},
		});
		playersTable.grantReadWriteData(verifySessionFn);
		sessionsTable.grantWriteData(verifySessionFn);

		api.addRoutes({
			path: '/auth/verify',
			methods: [HttpMethod.POST],
			integration: new HttpLambdaIntegration('VerifySessionIntegration', verifySessionFn),
		});

		new cdk.CfnOutput(this, 'ApiUrl', { value: api.apiEndpoint });
	}
}
