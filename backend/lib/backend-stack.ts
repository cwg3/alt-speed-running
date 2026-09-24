import * as path from 'path';
import * as cdk from 'aws-cdk-lib/core';
import { Construct } from 'constructs';
import { NodejsFunction } from 'aws-cdk-lib/aws-lambda-nodejs';
import { Runtime } from 'aws-cdk-lib/aws-lambda';
import { HttpApi, HttpMethod } from 'aws-cdk-lib/aws-apigatewayv2';
import { HttpLambdaIntegration } from 'aws-cdk-lib/aws-apigatewayv2-integrations';
import { AttributeType, BillingMode, Table } from 'aws-cdk-lib/aws-dynamodb';
import { BlockPublicAccess, Bucket } from 'aws-cdk-lib/aws-s3';

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
			// Reaps rows left behind by clients that quit mid-search.
			// queueJoin ignores stale rows regardless; this just keeps
			// the table from growing without bound.
			timeToLiveAttribute: 'expiresAt',
		});

		const matchesTable = new Table(this, 'MatchesTable', {
			partitionKey: { name: 'matchId', type: AttributeType.STRING },
			billingMode: BillingMode.PAY_PER_REQUEST,
		});

		// One row per PLAYER per match, newest first.
		//
		// The matches table is keyed by matchId, so "which matches did
		// this player play" would be a full scan - and a match has TWO
		// players, which a single GSI cannot index from one item. So
		// each completed match writes a row per player here instead.
		//
		// This is also where a replay pointer belongs, which is why it
		// is worth building properly now rather than scanning and
		// redoing it.
		const matchHistoryTable = new Table(this, 'MatchHistoryTable', {
			partitionKey: { name: 'uuid', type: AttributeType.STRING },
			// Descending reads come from ScanIndexForward: false at
			// query time; the key itself is just the completion time.
			sortKey: { name: 'completedAt', type: AttributeType.NUMBER },
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
				MATCH_HISTORY_TABLE_NAME: matchHistoryTable.tableName,
				SEED_POOL_TABLE_NAME: seedPoolTable.tableName,
				// TEMPORARY: restricts which seed types are drawn.
				//
				// Empty means draw evenly across all five, which is what
				// a real ladder must do - an even mix is the whole point
				// of having five types, and this MUST be cleared before
				// anyone but us plays.
				//
				// Set here in the stack rather than by hand on the
				// function, because a `cdk deploy` overwrites the
				// environment from this file: a value set through the
				// console silently disappeared on the next deploy and
				// nobody noticed until the draws changed.
				//
				// Currently limited to the two land openings while the
				// ocean routes are being practised - buried treasure and
				// shipwreck both need the kelp/ravine/bubble technique.
				// Default EMPTY: draw evenly across all five types, which
				// is what a ladder has to do. Biasing is opt-in, by
				// exporting SEED_TYPE_BIAS before a deploy.
				//
				// It defaulted to 'village,desert_temple' so that
				// practising a specific opening was the easy path. That
				// is backwards for anything but a solo session: whoever
				// deployed next without thinking about it would quietly
				// narrow every player's draws to two of five types, and
				// nothing would look broken. The live function is
				// currently empty while the synthesised template says
				// otherwise, which is exactly the kind of drift a
				// default like that produces.
				SEED_TYPE_BIAS: process.env.SEED_TYPE_BIAS ?? '',
			},
		});
		sessionsTable.grantReadData(queueJoinFn);
		// Write access is needed to stamp currentMatchId on both players
		// when a match is created.
		playersTable.grantReadWriteData(queueJoinFn);
		queueTable.grantReadWriteData(queueJoinFn);
		// Reads too: an existing pending match is looked up so the player
		// who did not create it can still be told about it.
		matchesTable.grantReadWriteData(queueJoinFn);
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
				MATCH_HISTORY_TABLE_NAME: matchHistoryTable.tableName,
			},
		});
		sessionsTable.grantReadData(completeMatchFn);
		playersTable.grantReadWriteData(completeMatchFn);
		matchesTable.grantReadWriteData(completeMatchFn);
		// Settles matches, so it writes the history rows.
		matchHistoryTable.grantWriteData(completeMatchFn);

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
				MATCH_HISTORY_TABLE_NAME: matchHistoryTable.tableName,
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
				MATCH_HISTORY_TABLE_NAME: matchHistoryTable.tableName,
				PLAYERS_TABLE_NAME: playersTable.tableName,
			},
		});
		sessionsTable.grantReadData(liveMatchFn);
		// Writes now: the poll doubles as a heartbeat, and resolves a
		// match whose other player has gone silent.
		matchesTable.grantReadWriteData(liveMatchFn);
		// Settles matches, so it writes the history rows.
		matchHistoryTable.grantWriteData(liveMatchFn);
		playersTable.grantReadWriteData(liveMatchFn);

		api.addRoutes({
			path: '/matches/{matchId}/live',
			methods: [HttpMethod.GET],
			integration: new HttpLambdaIntegration('LiveMatchIntegration', liveMatchFn),
		});

		// Claims a player's run start, once and only once per match.
		// The client picks the moment (its first playable tick, so
		// loading time is not charged to the run); the server owns the
		// record, so quitting and rejoining cannot mint a fresh 0:00.
		const startRunFn = new NodejsFunction(this, 'StartRunFunction', {
			entry: path.join(__dirname, '..', 'lambda', 'startRun.ts'),
			runtime: Runtime.NODEJS_24_X,
			handler: 'handler',
			environment: {
				SESSIONS_TABLE_NAME: sessionsTable.tableName,
				MATCHES_TABLE_NAME: matchesTable.tableName,
				MATCH_HISTORY_TABLE_NAME: matchHistoryTable.tableName,
			},
		});
		sessionsTable.grantReadData(startRunFn);
		matchesTable.grantReadWriteData(startRunFn);

		api.addRoutes({
			path: '/matches/start',
			methods: [HttpMethod.POST],
			integration: new HttpLambdaIntegration('StartRunIntegration', startRunFn),
		});

		// Both players voting that a seed is unplayable voids the match
		// with no rating change and pulls the seed from circulation.
		const badSeedFn = new NodejsFunction(this, 'BadSeedFunction', {
			entry: path.join(__dirname, '..', 'lambda', 'badSeed.ts'),
			runtime: Runtime.NODEJS_24_X,
			handler: 'handler',
			environment: {
				SESSIONS_TABLE_NAME: sessionsTable.tableName,
				MATCHES_TABLE_NAME: matchesTable.tableName,
				MATCH_HISTORY_TABLE_NAME: matchHistoryTable.tableName,
				SEED_POOL_TABLE_NAME: seedPoolTable.tableName,
			},
		});
		sessionsTable.grantReadData(badSeedFn);
		matchesTable.grantReadWriteData(badSeedFn);
		seedPoolTable.grantReadWriteData(badSeedFn);

		api.addRoutes({
			path: '/matches/bad-seed',
			methods: [HttpMethod.POST],
			integration: new HttpLambdaIntegration('BadSeedIntegration', badSeedFn),
		});

		const forfeitMatchFn = new NodejsFunction(this, 'ForfeitMatchFunction', {
			entry: path.join(__dirname, '..', 'lambda', 'forfeitMatch.ts'),
			runtime: Runtime.NODEJS_24_X,
			handler: 'handler',
			environment: {
				SESSIONS_TABLE_NAME: sessionsTable.tableName,
				PLAYERS_TABLE_NAME: playersTable.tableName,
				MATCHES_TABLE_NAME: matchesTable.tableName,
				MATCH_HISTORY_TABLE_NAME: matchHistoryTable.tableName,
			},
		});
		sessionsTable.grantReadData(forfeitMatchFn);
		playersTable.grantReadWriteData(forfeitMatchFn);
		matchesTable.grantReadWriteData(forfeitMatchFn);
		// Settles matches, so it writes the history rows.
		matchHistoryTable.grantWriteData(forfeitMatchFn);

		api.addRoutes({
			path: '/matches/forfeit',
			methods: [HttpMethod.POST],
			integration: new HttpLambdaIntegration('ForfeitMatchIntegration', forfeitMatchFn),
		});

		// Match replays: position timelines, gzipped. Private - these are
		// player movement records, not public artefacts. Retained on
		// stack deletion so an integrity investigation can outlive a
		// redeploy.
		const replayBucket = new Bucket(this, 'ReplayBucket', {
			blockPublicAccess: BlockPublicAccess.BLOCK_ALL,
			removalPolicy: cdk.RemovalPolicy.RETAIN,
		});

		const uploadReplayFn = new NodejsFunction(this, 'UploadReplayFunction', {
			entry: path.join(__dirname, '..', 'lambda', 'uploadReplay.ts'),
			runtime: Runtime.NODEJS_24_X,
			handler: 'handler',
			timeout: cdk.Duration.seconds(30),
			memorySize: 512,
			environment: {
				SESSIONS_TABLE_NAME: sessionsTable.tableName,
				MATCHES_TABLE_NAME: matchesTable.tableName,
				MATCH_HISTORY_TABLE_NAME: matchHistoryTable.tableName,
				REPLAY_BUCKET: replayBucket.bucketName,
			},
		});
		sessionsTable.grantReadData(uploadReplayFn);
		matchesTable.grantReadWriteData(uploadReplayFn);
		replayBucket.grantPut(uploadReplayFn);

		api.addRoutes({
			path: '/matches/replay',
			methods: [HttpMethod.POST],
			integration: new HttpLambdaIntegration('UploadReplayIntegration', uploadReplayFn),
		});

		const matchHistoryFn = new NodejsFunction(this, 'MatchHistoryFunction', {
			entry: path.join(__dirname, '..', 'lambda', 'matchHistory.ts'),
			runtime: Runtime.NODEJS_24_X,
			handler: 'handler',
			timeout: cdk.Duration.seconds(10),
			memorySize: 256,
			environment: {
				SESSIONS_TABLE_NAME: sessionsTable.tableName,
				MATCH_HISTORY_TABLE_NAME: matchHistoryTable.tableName,
			},
		});
		sessionsTable.grantReadData(matchHistoryFn);
		// Read only. This endpoint answers questions; it never writes
		// history, which only the settle path does.
		matchHistoryTable.grantReadData(matchHistoryFn);

		api.addRoutes({
			path: '/players/me/matches',
			methods: [HttpMethod.GET],
			integration: new HttpLambdaIntegration('MatchHistoryIntegration', matchHistoryFn),
		});

		const getReplayFn = new NodejsFunction(this, 'GetReplayFunction', {
			entry: path.join(__dirname, '..', 'lambda', 'getReplay.ts'),
			runtime: Runtime.NODEJS_24_X,
			handler: 'handler',
			timeout: cdk.Duration.seconds(30),
			// Two traces of tens of thousands of samples are decompressed
			// and re-serialised here, so this needs more room than a
			// table read.
			memorySize: 1024,
			environment: {
				SESSIONS_TABLE_NAME: sessionsTable.tableName,
				MATCHES_TABLE_NAME: matchesTable.tableName,
				REPLAY_BUCKET: replayBucket.bucketName,
			},
		});
		sessionsTable.grantReadData(getReplayFn);
		matchesTable.grantReadData(getReplayFn);
		// The bucket had grantPut and nothing else - there was no read
		// path at all until now, which is why no replay could be played.
		replayBucket.grantRead(getReplayFn);

		api.addRoutes({
			path: '/matches/{matchId}/replay',
			methods: [HttpMethod.GET],
			integration: new HttpLambdaIntegration('GetReplayIntegration', getReplayFn),
		});

		new cdk.CfnOutput(this, 'ApiUrl', { value: api.apiEndpoint });
		new cdk.CfnOutput(this, 'MatchHistoryTableName', { value: matchHistoryTable.tableName });
		new cdk.CfnOutput(this, 'ReplayBucketName', { value: replayBucket.bucketName });
		new cdk.CfnOutput(this, 'SeedPoolTableName', { value: seedPoolTable.tableName });
	}
}
