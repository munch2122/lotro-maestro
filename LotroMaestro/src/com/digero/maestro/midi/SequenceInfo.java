package com.digero.maestro.midi;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;

import com.sun.media.sound.MidiUtils;
import com.sun.media.sound.MidiUtils.TempoCache;

/**
 * Container for a MIDI sequence. If necessary, converts type 0 MIDI files to
 * type 1.
 */
public class SequenceInfo implements MidiConstants {
	private Sequence sequence;
	private String title;
	private int tempo;
	private TrackInfo[] trackInfo;
	private List<TrackInfo> trackInfoList;

	public SequenceInfo(File midiFile) throws InvalidMidiDataException, IOException {
		sequence = MidiSystem.getSequence(midiFile);

		// Since the drum track merge is only applicable to type 1 midi sequences, 
		// do it before we convert this sequence, to avoid doing unnecessary work
//		mergeDrumTracks(sequence);

		convertToType1(sequence);

		Track[] tracks = sequence.getTracks();
		if (tracks.length == 0) {
			throw new InvalidMidiDataException("The MIDI file doesn't have any tracks");
		}

		MidiUtils.TempoCache tempoCache = new MidiUtils.TempoCache(sequence);
		trackInfo = new TrackInfo[tracks.length];
		trackInfoList = Collections.unmodifiableList(Arrays.asList(trackInfo));
		for (int i = 0; i < tracks.length; i++) {
			trackInfo[i] = new TrackInfo(this, tracks[i], i, tempoCache);
		}

		tempo = findMainTempo(sequence, tempoCache);

		if (trackInfo[0].hasName()) {
			title = trackInfo[0].getName();
		}
		else {
			title = midiFile.getName();
			int dot = title.lastIndexOf('.');
			if (dot > 0)
				title = title.substring(0, dot);
		}
	}

	public Sequence getSequence() {
		return sequence;
	}

	public String getTitle() {
		return title;
	}

	public int getTrackCount() {
		return trackInfo.length;
	}

	public TrackInfo getTrackInfo(int track) {
		return trackInfo[track];
	}
	
	public List<TrackInfo> getTrackList(){
		return trackInfoList;
	}

	public int getTempo() {
		return tempo;
	}

	public KeySignature getKeySignature() {
		for (TrackInfo track : trackInfo) {
			if (track.getKeySignature() != null)
				return track.getKeySignature();
		}
		return KeySignature.C_MAJOR;
	}
	
	public TimeSignature getTimeSignature() {
		for (TrackInfo track : trackInfo) {
			if (track.getTimeSignature() != null)
				return track.getTimeSignature();
		}
		return TimeSignature.FOUR_FOUR;
	}

	@Override
	public String toString() {
		return getTitle();
	}

	public static int findMainTempo(Sequence sequence, TempoCache tempoCache) {
		Map<Integer, Long> tempoLengths = new HashMap<Integer, Long>();

		long bestTempoLength = 0;
		int bestTempoBPM = 120;

		long curTempoStart = 0;
		int curTempoBPM = 120;

		Track track0 = sequence.getTracks()[0];
		int c = track0.size();
		for (int i = 0; i < c; i++) {
			MidiEvent evt = track0.get(i);
			MidiMessage msg = evt.getMessage();
			if (MidiUtils.isMetaTempo(msg)) {
				long nextTempoStart = MidiUtils.tick2microsecond(sequence, evt.getTick(), tempoCache);

				Long lengthObj = tempoLengths.get(curTempoBPM);
				long length = (lengthObj == null) ? 0 : lengthObj;
				length += nextTempoStart - curTempoStart;

				if (length > bestTempoLength) {
					bestTempoLength = length;
					bestTempoBPM = curTempoBPM;
				}

				tempoLengths.put(curTempoBPM, length);

				curTempoBPM = (int) (MidiUtils.convertTempo(MidiUtils.getTempoMPQ(msg)) + 0.5);
				curTempoStart = nextTempoStart;
			}
		}

		Long lengthObj = tempoLengths.get(curTempoBPM);
		long length = (lengthObj == null) ? 0 : lengthObj;
		length += sequence.getMicrosecondLength() - curTempoStart;

		if (length > bestTempoLength) {
			bestTempoLength = length;
			bestTempoBPM = curTempoBPM;
		}

		return bestTempoBPM;
	}

	/**
	 * Separates the MIDI file to have one track per channel (Type 1).
	 */
	public static void convertToType1(Sequence song) {
		if (song.getTracks().length == 1) {
			Track track0 = song.getTracks()[0];
			Track[] tracks = new Track[CHANNEL_COUNT];

			int trackNumber = 1;
			int i = 0;
			while (i < track0.size()) {
				MidiEvent evt = track0.get(i);
				if (evt.getMessage() instanceof ShortMessage) {
					int chan = ((ShortMessage) evt.getMessage()).getChannel();
					if (tracks[chan] == null) {
						tracks[chan] = song.createTrack();
						String trackName = (chan == DRUM_CHANNEL) ? "Drums" : ("Track " + trackNumber);
						trackNumber++;
						tracks[chan].add(MidiFactory.createTrackNameEvent(trackName));
					}
					tracks[chan].add(evt);
					if (track0.remove(evt))
						continue;
				}
				i++;
			}
		}
	}

	/**
	 * Ensures that there is at most one drum track, and that track contains
	 * only drum notes.
	 */
	public static void mergeDrumTracks(Sequence song) {
		Track[] tracks = song.getTracks();
		// This doesn't work on Type 0 MIDI files
		if (tracks.length <= 1)
			return;

		final int DRUMS = 0x1;
		final int NOTES = 0x2;
		final int MIXED = DRUMS | NOTES;

		int[] trackContents = new int[tracks.length];
		int firstDrumTrack = -1;
		boolean hasMultipleDrumTracks = false;
		boolean hasMixed = false;

		for (int i = 0; i < tracks.length; i++) {
			Track track = tracks[i];
			for (int j = 0; j < track.size(); j++) {
				MidiEvent evt = track.get(j);
				MidiMessage msg = evt.getMessage();
				if (msg instanceof ShortMessage) {
					ShortMessage m = (ShortMessage) msg;
					if (m.getCommand() == ShortMessage.NOTE_ON) {
						if (m.getChannel() == DRUM_CHANNEL) {
							trackContents[i] |= DRUMS;
							if (firstDrumTrack == -1)
								firstDrumTrack = i;
							else if (firstDrumTrack != i)
								hasMultipleDrumTracks = true;
						}
						else {
							trackContents[i] |= NOTES;
						}

						if (trackContents[i] == MIXED) {
							hasMixed = true;
							break;
						}
					}
				}
			}
		}

		// If there are 0 or 1 pure drum tracks, don't need to do anything
		if (!hasMultipleDrumTracks && !hasMixed)
			return;

		Track drumTrack = song.createTrack();
		drumTrack.add(MidiFactory.createTrackNameEvent("Drums"));
		for (int i = firstDrumTrack; i < tracks.length; i++) {
			Track track = tracks[i];
			if (trackContents[i] == DRUMS) {
				// Pure drum track: copy all events except track name
				for (int j = 0; j < track.size(); j++) {
					MidiEvent evt = track.get(j);
					MidiMessage msg = evt.getMessage();
					if ((msg instanceof MetaMessage) && ((MetaMessage) msg).getType() == META_TRACK_NAME)
						continue;
					drumTrack.add(evt);
				}
				song.deleteTrack(track);
			}
			else if (trackContents[i] == MIXED) {
				// Mixed track: copy only the events on the drum channel
				for (int j = 0; j < track.size(); j++) {
					MidiEvent evt = track.get(j);
					MidiMessage msg = evt.getMessage();
					if ((msg instanceof ShortMessage) && ((ShortMessage) msg).getChannel() == DRUM_CHANNEL) {
						drumTrack.add(evt);
						if (track.remove(evt))
							j--;
					}
				}
			}
		}
	}
}