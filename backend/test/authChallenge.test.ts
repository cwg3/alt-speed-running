// The serverId /auth/challenge hands out.
//
// Mojang refuses a serverId over 20 characters or containing '-' or
// '_', and the client's old base64url ids failed on exactly that, now
// and then - an intermittent login failure that looked like a server
// problem. Pinned here so a change of encoding cannot bring it back.
import { newServerId } from '../lambda/authChallenge';

describe('newServerId', () => {
	it('fits Mojang\'s serverId rules', () => {
		for (let i = 0; i < 1000; i++) {
			expect(newServerId()).toMatch(/^[0-9a-f]{16}$/);
		}
	});

	it('does not repeat', () => {
		const ids = new Set<string>();
		for (let i = 0; i < 1000; i++) {
			ids.add(newServerId());
		}
		expect(ids.size).toBe(1000);
	});
});
