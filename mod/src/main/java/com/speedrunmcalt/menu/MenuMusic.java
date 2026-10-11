package com.speedrunmcalt.menu;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.MusicType;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.MusicSound;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/**
 * alt's own menu music, in place of the vanilla menu tracks.
 *
 * Played wherever vanilla would play MusicType.MENU - the title screen
 * and every menu with no world loaded - so in-game music, the credits
 * and everything else stay vanilla. It goes through MusicTracker like
 * any track, on the Music channel, so the Music slider controls it.
 * Removing the mod brings the C418 menu music straight back; no vanilla
 * asset is touched.
 *
 * Shipped as sounds/menu_loop.ogg, streamed: an original track by the
 * project's author ("init voice"), resampled to 44.1 kHz. Zero delay
 * either side, so the tracker restarts it the tick it ends.
 * brand/gen_menu_music.py renders a synthesised stand-in to the same
 * path.
 *
 * Two holds keep the tracker quiet (MusicTrackerMixin):
 *
 *   - until PowerOn has finished, so the first note follows the set
 *     switching on rather than playing under the Mojang splash;
 *   - from a match being found until a world loads, so the level-up
 *     chime and the tune-in have the room to themselves. Without it
 *     the tracker would restart the loop the tick after a cue ducked
 *     it - zero delay means zero ducking. Cleared when the world
 *     loads, or the title screen or lobby is back, so after the
 *     match - or a match that fell through - the menus have their
 *     music again.
 *
 * The track is played as a Track (MusicTrackerMixin swaps it in for the
 * PositionedSoundInstance vanilla would make), whose volume can be
 * walked down: a match being found or Quit Game fades it out over
 * FADE_TICKS instead of cutting it dead. The first time it starts in a
 * launch, a NowPlayingToast names it.
 */
public final class MenuMusic {
	private static final SoundEvent LOOP =
			new SoundEvent(new Identifier("speedrunmcalt", "menu_loop"));
	private static final MusicSound MUSIC = new MusicSound(LOOP, 0, 0, true);

	/** The song and its artist, as the now-playing toast shows them. */
	public static final String TITLE = "init voice";
	public static final String ARTIST = "@ 8-bit life";

	/** 1.25 seconds - long enough to read as a fade under a chime. */
	private static final int FADE_TICKS = 25;

	/** Set off the client thread by Cues, hence volatile. */
	private static volatile boolean matchStarting;

	private static volatile Track playing;
	private static boolean toasted;

	private MenuMusic() {
	}

	/** Vanilla's choice with the menu tracks swapped for alt's. */
	public static MusicSound choose(MusicSound vanilla) {
		return vanilla == MusicType.MENU ? MUSIC : vanilla;
	}

	/** Called when a match is found: fade out, then silence until the world is in. */
	public static void matchStarting() {
		matchStarting = true;
		fadeOut();
	}

	/** Fades the menu track out if it is playing; Quit Game uses this too. */
	public static void fadeOut() {
		Track track = playing;
		if (track != null) {
			track.fading = true;
		}
	}

	/**
	 * Whether the menu track is on its way out. Cues skip their music
	 * duck while it is, which would otherwise cut the fade off dead.
	 */
	public static boolean fading() {
		Track track = playing;
		return track != null && track.fading && !track.isDone();
	}

	/** Whether this is alt's menu music, which MusicTrackerMixin plays as a Track. */
	public static boolean isMenuMusic(MusicSound type) {
		return type == MUSIC;
	}

	/** A fresh instance of the track, and the toast the first time in a launch. */
	public static SoundInstance start(MinecraftClient client) {
		Track track = new Track();
		playing = track;
		if (!toasted) {
			toasted = true;
			client.getToastManager().add(new NowPlayingToast(TITLE, ARTIST));
		}
		return track;
	}

	/**
	 * The music as a tickable sound, so its volume can move. Otherwise
	 * exactly what PositionedSoundInstance.music builds: music channel,
	 * full volume and pitch, no attenuation, relative to the listener -
	 * yarn calls that last flag "looping", but it is the one music()
	 * sets true and it means relative.
	 */
	private static final class Track extends MovingSoundInstance {
		private volatile boolean fading;
		private float level = 1.0f;

		Track() {
			super(LOOP, SoundCategory.MUSIC);
			this.repeat = false;
			this.repeatDelay = 0;
			this.volume = 1.0f;
			this.pitch = 1.0f;
			this.attenuationType = SoundInstance.AttenuationType.NONE;
			this.looping = true;
		}

		@Override
		public void tick() {
			if (!this.fading) {
				return;
			}
			this.level -= 1.0f / FADE_TICKS;
			if (this.level <= 0.0f) {
				this.level = 0.0f;
				this.setDone();
			}
			this.volume = this.level;
		}
	}

	/** Whether MusicTracker should sit this tick out. */
	public static boolean holding(MinecraftClient client) {
		// Back on the title screen or in the lobby with the matchmaker
		// no longer launching means no match is starting after all - a
		// dodge or a failed load lands here. A race-a-pace can be found
		// while the lobby is still showing, hence the state check.
		boolean menu = client.currentScreen instanceof net.minecraft.client.gui.screen.TitleScreen
				|| client.currentScreen instanceof AltMenuScreen;
		if (client.world != null || (menu && Matchmaker.state() != Matchmaker.State.LAUNCHING)) {
			matchStarting = false;
		}
		if (client.world != null) {
			return false;
		}
		return matchStarting || !PowerOn.isDone();
	}
}
