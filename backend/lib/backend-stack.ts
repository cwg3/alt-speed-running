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

		const queueTable = new Table(this, 'QueueTable', {
			partitionKey: { name: 'uuid', type: AttributeType.STRING },
			billingMode: BillingMode.PAY_PER_REQUEST,
		});

		const matchesTable = new Table(this, 'MatchesTable', {
			partitionKey: { name: 'matchId', type: AttributeType.STRING },
			billingMode: BillingMode.PAY_PER_REQUEST,
		});

		// Populated offline by scripts/loadSeedPool.ts from the Phase 1
		// cubiomes output (seed-filter/output/match_seeds.json) - this
		// table is the bridge between the seed-filtering tool and actual
		// matches. `used` + conditional updates make claiming a pair
		// atomic, so two matches created at the same instant can't collide
		// on the same seed.
		const seedPoolTable = new Table(this, 'SeedPoolTable', {
			partitionKey: { name: 'seedPairId', type: AttributeType.STRING },
			billingMode: BillingMode.PAY_PER_REQUEST,
		});

		const queueJoinFn = new NodejsFunction(this, 'QueueJoinFunction', {
			entry: path.join(__dirname, '..', 'lambda', 'queueJoin.ts'),
			runtime: Runtime.NODEJS_24_X,
			handler: 'handler',
			environment: {
				SESSIONS_TABLE_NAME: sessionsTable.tableName,
				PLAYERS_TABLE_NAME: playersTable.tableName,
				QUEUE_TABLE_NAME: queueTable.tableName,
				MATCHES_TABLE_NAME: matchesTable.tableName,
				SEED_POOL_TABLE_NAME: seedPoolTable.tableName,
			},
		});
		sessionsTable.grantReadData(queueJoinFn);
		playersTable.grantReadData(queueJoinFn);
		queueTable.grantReadWriteData(queueJoinFn);
		matchesTable.grantWriteData(queueJoinFn);
		seedPoolTable.grantReadWriteData(queueJoinFn);

		api.addRoutes({
			path: '/queue/join',
			methods: [HttpMethod.POST],
			integration: new HttpLambdaIntegration('QueueJoinIntegration', queueJoinFn),
		});

		const completeMatchFn = new NodejsFunction(this, 'CompleteMatchFunction', {
			entry: path.join(__dirname, '..', 'lambda', 'completeMatch.ts'),
			runtime: Runtime.NODEJS_24_X,
			handler: 'handler',
			environment: {
				SESSIONS_TABLE_NAME: sessionsTable.tableName,
				PLAYERS_TABLE_NAME: playersTable.tableName,
				MATCHES_TABLE_NAME: matchesTable.tableName,
			},
		});
		sessionsTable.grantReadData(completeMatchFn);
		playersTable.grantReadWriteData(completeMatchFn);
		matchesTable.grantReadWriteData(completeMatchFn);

		api.addRoutes({
			path: '/matches/complete',
			methods: [HttpMethod.POST],
			integration: new HttpLambdaIntegration('CompleteMatchIntegration', completeMatchFn),
		});

		const reportSplitFn = new NodejsFunction(this, 'ReportSplitFunction', {
			entry: path.join(__dirname, '..', 'lambda', 'reportSplit.ts'),
			runtime: Runtime.NODEJS_24_X,
			handler: 'handler',
			environment: {
				SESSIONS_TABLE_NAME: sessionsTable.tableName,
				PLAYERS_TABLE_NAME: playersTable.tableName,
				MATCHES_TABLE_NAME: matchesTable.tableName,
			},
		});
		sessionsTable.grantReadData(reportSplitFn);
		playersTable.grantReadWriteData(reportSplitFn);
		matchesTable.grantReadWriteData(reportSplitFn);

		api.addRoutes({
			path: '/matches/split',
			methods: [HttpMethod.POST],
			integration: new HttpLambdaIntegration('ReportSplitIntegration', reportSplitFn),
		});

		const liveMatchFn = new NodejsFunction(this, 'LiveMatchFunction', {
			entry: path.join(__dirname, '..', 'lambda', 'liveMatch.ts'),
			runtime: Runtime.NODEJS_24_X,
			handler: 'handler',
			environment: {
				SESSIONS_TABLE_NAME: sessionsTable.tableName,
				MATCHES_TABLE_NAME: matchesTable.tableName,
			},
		});
		sessionsTable.grantReadData(liveMatchFn);
		matchesTable.grantReadData(liveMatchFn);

		api.addRoutes({
			path: '/matches/{matchId}/live',
			methods: [HttpMethod.GET],
			integration: new HttpLambdaIntegration('LiveMatchIntegration', liveMatchFn),
		});

		new cdk.CfnOutput(this, 'ApiUrl', { value: api.apiEndpoint });
		new cdk.CfnOutput(this, 'SeedPoolTableName', { value: seedPoolTable.tableName });
	}
}
