package com.digero.common.midi;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Transmitter;
import javax.swing.Timer;

import com.digero.common.util.IDiscardable;
import com.sun.media.sound.MidiUtils;
import com.sun.media.sound.MidiUtils.TempoCache;

public class SequencerWrapper implements IMidiConstants, IDiscardable
{
	public static final int UPDATE_FREQUENCY_MILLIS = 25;
	public static final long UPDATE_FREQUENCY_MICROS = UPDATE_FREQUENCY_MILLIS * 1000;

	protected Sequencer sequencer;
	private Receiver receiver;
	private Transmitter transmitter;
	private List<Transceiver> transceivers = new ArrayList<Transceiver>();
	private long dragPosition;
	private boolean isDragging;
	private TempoCache tempoCache = new TempoCache();

	private Timer updateTimer = new Timer(UPDATE_FREQUENCY_MILLIS, new TimerActionListener());
	private long lastUpdatePosition = -1;
	private boolean lastRunning = false;

	private List<SequencerListener> listeners = null;
	private List<SequencerListener> listenersAddedWhileFiring = null;
	private List<SequencerListener> listenersRemovedWhileFiring = null;
	private boolean isFiringEvent = false;

	public SequencerWrapper() throws MidiUnavailableException
	{
		sequencer = MidiSystem.getSequencer(false);
		sequencer.open();
		transmitter = sequencer.getTransmitter();
		receiver = createReceiver();
		transmitter.setReceiver(receiver);
	}

	protected Receiver createReceiver() throws MidiUnavailableException
	{
		return MidiSystem.getReceiver();
	}

	@Override public void discard()
	{
		if (sequencer != null)
		{
			stop();
		}

		listeners = null;

		if (updateTimer != null)
			updateTimer.stop();

		if (transceivers != null)
		{
			for (Transceiver t : transceivers)
				t.close();
			transceivers = null;
		}

		if (transmitter != null)
			transmitter.close();

		if (receiver != null)
			receiver.close();

		if (sequencer != null)
			sequencer.close();
	}

	public void addTransceiver(Transceiver transceiver)
	{
		Transmitter lastTransmitter = transmitter;
		if (!transceivers.isEmpty())
			lastTransmitter = transceivers.get(transceivers.size() - 1);

		// Hook up the transceiver in the chain
		lastTransmitter.setReceiver(transceiver);
		transceiver.setReceiver(receiver);

		transceivers.add(transceiver);
	}

	private class TimerActionListener implements ActionListener
	{
		@Override public void actionPerformed(ActionEvent e)
		{
			if (sequencer != null && sequencer.isOpen())
			{
				long songPos = sequencer.getMicrosecondPosition();
				if (songPos >= getLength())
				{
					// There's a bug in Sun's RealTimeSequencer, where there is a possible 
					// deadlock when calling setMicrosecondPosition(0) exactly when the sequencer 
					// hits the end of the sequence.  It looks like it's safe to call 
					// sequencer.setTickPosition(0).
					sequencer.stop();
					sequencer.setTickPosition(0);
					lastUpdatePosition = songPos;
				}
				else
				{
					if (lastUpdatePosition != songPos)
					{
						lastUpdatePosition = songPos;
						fireChangeEvent(SequencerProperty.POSITION);
					}
					boolean running = sequencer.isRunning();
					if (lastRunning != running)
					{
						lastRunning = running;
						if (running)
							updateTimer.start();
						else
							updateTimer.stop();
						fireChangeEvent(SequencerProperty.IS_RUNNING);
					}
				}
			}
		}
	}

	public void reset(boolean fullReset)
	{
		stop();
		setPosition(0);

		if (fullReset)
		{
			Sequence seqSave = sequencer.getSequence();
			try
			{
				sequencer.setSequence((Sequence) null);
			}
			catch (InvalidMidiDataException e)
			{
				// This won't happen
				throw new RuntimeException(e);
			}

			sequencer.close();
			transmitter.close();
			receiver.close();

			try
			{
				sequencer = MidiSystem.getSequencer(false);
				sequencer.open();
				transmitter = sequencer.getTransmitter();
				receiver = createReceiver();
			}
			catch (MidiUnavailableException e1)
			{
				throw new RuntimeException(e1);
			}

			try
			{
				sequencer.setSequence(seqSave);
			}
			catch (InvalidMidiDataException e)
			{
				// This won't happen
				throw new RuntimeException(e);
			}

			// Hook up the transmitter to the receiver through any transceivers that we have
			Transmitter prevTransmitter = transmitter;
			for (Transceiver transceiver : transceivers)
			{
				prevTransmitter.setReceiver(transceiver);
				prevTransmitter = transceiver;
			}
			prevTransmitter.setReceiver(receiver);

			try
			{
				ShortMessage msg = new ShortMessage();
				msg.setMessage(ShortMessage.SYSTEM_RESET);
				receiver.send(msg, -1);
			}
			catch (InvalidMidiDataException e)
			{
				e.printStackTrace();
			}
		}
		else
		{ // Not a full reset
			boolean isOpen = sequencer.isOpen();
			try
			{
				if (!isOpen)
					sequencer.open();

				ShortMessage msg = new ShortMessage();
				for (int i = 0; i < CHANNEL_COUNT; i++)
				{
					msg.setMessage(ShortMessage.PROGRAM_CHANGE, i, 0, 0);
					receiver.send(msg, -1);
					msg.setMessage(ShortMessage.CONTROL_CHANGE, i, ALL_CONTROLLERS_OFF, 0);
					receiver.send(msg, -1);
				}
				msg.setMessage(ShortMessage.SYSTEM_RESET);
				receiver.send(msg, -1);
			}
			catch (MidiUnavailableException e)
			{
				// Ignore
			}
			catch (InvalidMidiDataException e)
			{
				// Ignore
				e.printStackTrace();
			}

			if (!isOpen)
				sequencer.close();
		}
	}

	public long getTickPosition()
	{
		return sequencer.getTickPosition();
	}

	public void setTickPosition(long tick)
	{
		if (tick != getTickPosition())
		{
			sequencer.setTickPosition(tick);
			lastUpdatePosition = sequencer.getMicrosecondPosition();
			fireChangeEvent(SequencerProperty.POSITION);
		}
	}

	public long getPosition()
	{
		return sequencer.getMicrosecondPosition();
	}

	public void setPosition(long position)
	{
		if (position == 0)
		{
			// Sun's RealtimeSequencer isn't entirely reliable when calling 
			// setMicrosecondPosition(0). Instead call setTickPosition(0),  
			// which has the same effect and isn't so buggy.
			setTickPosition(0);
		}
		else if (position != getPosition())
		{
			sequencer.setMicrosecondPosition(position);
			lastUpdatePosition = sequencer.getMicrosecondPosition();
			fireChangeEvent(SequencerProperty.POSITION);
		}
	}

	public long getLength()
	{
		return sequencer.getMicrosecondLength();
	}

	public float getTempoFactor()
	{
		return sequencer.getTempoFactor();
	}

	public void setTempoFactor(float tempo)
	{
		if (tempo != getTempoFactor())
		{
			sequencer.setTempoFactor(tempo);
			fireChangeEvent(SequencerProperty.TEMPO);
		}
	}

	public boolean isRunning()
	{
		return sequencer.isRunning();
	}

	public void setRunning(boolean isRunning)
	{
		if (isRunning != this.isRunning())
		{
			if (isRunning)
			{
				sequencer.start();
				updateTimer.start();
			}
			else
			{
				sequencer.stop();
				updateTimer.stop();
			}
			lastRunning = isRunning;

			fireChangeEvent(SequencerProperty.IS_RUNNING);
		}
	}

	public void start()
	{
		setRunning(true);
	}

	public void stop()
	{
		setRunning(false);
	}

	public boolean getTrackMute(int track)
	{
		return sequencer.getTrackMute(track);
	}

	public void setTrackMute(int track, boolean mute)
	{
		if (mute != this.getTrackMute(track))
		{
			sequencer.setTrackMute(track, mute);
			fireChangeEvent(SequencerProperty.TRACK_ACTIVE);
		}
	}

	public boolean getTrackSolo(int track)
	{
		return sequencer.getTrackSolo(track);
	}

	public void setTrackSolo(int track, boolean solo)
	{
		if (solo != this.getTrackSolo(track))
		{
			sequencer.setTrackSolo(track, solo);
			fireChangeEvent(SequencerProperty.TRACK_ACTIVE);
		}
	}

	/**
	 * Takes into account both muting and solo.
	 */
	public boolean isTrackActive(int track)
	{
		Sequence song = sequencer.getSequence();

		if (song == null)
			return true;

		if (sequencer.getTrackSolo(track))
			return true;

		for (int i = song.getTracks().length - 1; i >= 0; --i)
		{
			if (i != track && sequencer.getTrackSolo(i))
				return false;
		}

		return !sequencer.getTrackMute(track);
	}

	/**
	 * Overriden by NoteFilterSequencerWrapper. On SequencerWrapper for
	 * convienience.
	 */
	public boolean isNoteActive(int noteId)
	{
		return true;
	}

	/**
	 * If dragging, returns the drag position. Otherwise returns the song
	 * position.
	 */
	public long getThumbPosition()
	{
		return isDragging() ? getDragPosition() : getPosition();
	}

	/** If dragging, returns the drag tick. Otherwise returns the song tick. */
	public long getThumbTick()
	{
		return isDragging() ? getDragTick() : getTickPosition();
	}

	public long getDragPosition()
	{
		return dragPosition;
	}

	public long getDragTick()
	{
		return MidiUtils.microsecond2tick(getSequence(), getDragPosition(), tempoCache);
	}

	public void setDragTick(long tick)
	{
		setDragPosition(MidiUtils.tick2microsecond(getSequence(), tick, tempoCache));
	}

	public void setDragPosition(long dragPosition)
	{
		if (this.dragPosition != dragPosition)
		{
			this.dragPosition = dragPosition;
			fireChangeEvent(SequencerProperty.DRAG_POSITION);
		}
	}

	public boolean isDragging()
	{
		return isDragging;
	}

	public void setDragging(boolean isDragging)
	{
		if (this.isDragging != isDragging)
		{
			this.isDragging = isDragging;
			fireChangeEvent(SequencerProperty.IS_DRAGGING);
		}
	}

	public void addChangeListener(SequencerListener l)
	{
		if (isFiringEvent)
		{
			if (listenersAddedWhileFiring == null)
				listenersAddedWhileFiring = new ArrayList<SequencerListener>();

			listenersAddedWhileFiring.add(l);
		}
		else
		{
			if (listeners == null)
				listeners = new ArrayList<SequencerListener>();

			listeners.add(l);
		}
	}

	public void removeChangeListener(SequencerListener l)
	{
		if (isFiringEvent)
		{
			if (listenersRemovedWhileFiring == null)
				listenersRemovedWhileFiring = new ArrayList<SequencerListener>();

			listenersRemovedWhileFiring.add(l);
		}
		else
		{
			if (listeners != null)
				listeners.remove(l);
		}
	}

	protected void fireChangeEvent(SequencerProperty property)
	{
		if (listeners != null)
		{
			isFiringEvent = true;
			try
			{
				SequencerEvent e = new SequencerEvent(this, property);
				for (SequencerListener l : listeners)
				{
					l.propertyChanged(e);
				}
			}
			finally
			{
				isFiringEvent = false;

				if (listenersAddedWhileFiring != null)
				{
					for (SequencerListener l : listenersAddedWhileFiring)
						listeners.add(l);

					listenersAddedWhileFiring = null;
				}
				if (listenersRemovedWhileFiring != null)
				{
					for (SequencerListener l : listenersRemovedWhileFiring)
						listeners.remove(l);

					listenersRemovedWhileFiring = null;
				}
			}
		}
	}

	public void setSequence(Sequence sequence) throws InvalidMidiDataException
	{
		if (sequencer.getSequence() != sequence)
		{
			boolean preLoaded = isLoaded();
			sequencer.setSequence(sequence);
			if (sequence != null)
				tempoCache.refresh(sequence);
			if (preLoaded != isLoaded())
				fireChangeEvent(SequencerProperty.IS_LOADED);
			fireChangeEvent(SequencerProperty.LENGTH);
			fireChangeEvent(SequencerProperty.SEQUENCE);
		}
	}

	public void clearSequence()
	{
		try
		{
			setSequence(null);
		}
		catch (InvalidMidiDataException e)
		{
			// This shouldn't happen
			throw new RuntimeException(e);
		}
	}

	public boolean isLoaded()
	{
		return sequencer.getSequence() != null;
	}

	public Sequence getSequence()
	{
		return sequencer.getSequence();
	}

	public Transmitter getTransmitter()
	{
		return transmitter;
	}

	public Receiver getReceiver()
	{
		return receiver;
	}

	public void open() throws MidiUnavailableException
	{
		sequencer.open();
	}

	public void close()
	{
		sequencer.close();
	}
}