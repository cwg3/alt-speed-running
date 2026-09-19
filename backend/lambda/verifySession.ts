import type { APIGatewayProxyEventV2, APIGatewayProxyResultV2 } from 'aws-lambda';

interface VerifyRequest {
	username: string;
	serverId: string;
}

interface MojangProfile {
	id: string;
	name: string;
}

// Confirms a client's Mojang session-join actually happened, proving
// account ownership without our backend ever seeing the player's access
// token. Same mechanism vanilla multiplayer servers have used for
// identity verification for years - see mod/.../auth/SessionAuth.java
// for the client side of this pair.
export const handler = async (
	event: APIGatewayProxyEventV2,
): Promise<APIGatewayProxyResultV2> => {
	let body: VerifyRequest;
	try {
		body = JSON.parse(event.body ?? '{}');
	} catch {
		return { statusCode: 400, body: JSON.stringify({ error: 'invalid JSON body' }) };
	}

	if (!body.username || !body.serverId) {
		return {
			statusCode: 400,
			body: JSON.stringify({ error: 'username and serverId are required' }),
		};
	}

	const url = `https://sessionserver.mojang.com/session/minecraft/hasJoined?username=${encodeURIComponent(body.username)}&serverId=${encodeURIComponent(body.serverId)}`;
	const res = await fetch(url);

	// Confirmed live (not assumed from docs): Mojang returns HTTP 204 with
	// an empty body for a non-matching join - that's the genuine
	// "verification failed" case, not a transport error. A literal "null"
	// body has also been documented historically, so both are handled.
	if (res.status === 204) {
		return {
			statusCode: 401,
			body: JSON.stringify({ error: 'session verification failed - no matching join' }),
		};
	}

	if (res.status !== 200) {
		return {
			statusCode: 502,
			body: JSON.stringify({ error: `Mojang session server returned HTTP ${res.status}` }),
		};
	}

	const text = await res.text();
	if (!text || text.trim() === 'null') {
		return {
			statusCode: 401,
			body: JSON.stringify({ error: 'session verification failed - no matching join' }),
		};
	}

	const profile = JSON.parse(text) as MojangProfile;
	return {
		statusCode: 200,
		headers: { 'content-type': 'application/json' },
		body: JSON.stringify({ uuid: profile.id, username: profile.name }),
	};
};
