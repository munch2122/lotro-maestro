package com.digero.common.view;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import com.digero.common.abc.Dynamics;
import com.digero.common.midi.SequencerEvent;
import com.digero.common.midi.SequencerListener;
import com.digero.common.midi.SequencerProperty;
import com.digero.common.midi.SequencerWrapper;
import com.digero.common.util.IDisposable;
import com.digero.common.util.Util;
import com.digero.maestro.midi.NoteEvent;
import com.digero.maestro.midi.TrackInfo;

public class NoteGraph extends JPanel implements SequencerListener, IDisposable {
	protected final SequencerWrapper sequencer;
	protected TrackInfo trackInfo;

	protected final int MIN_RENDERED;
	protected final int MAX_RENDERED;
	protected final double NOTE_WIDTH_PX;
	protected final double NOTE_HEIGHT_PX;
	private final double NOTE_ON_OUTLINE_WIDTH_PX = 0.5;
	private final double NOTE_ON_EXTRA_HEIGHT_PX = 0.5;

	private ColorTable noteColor = ColorTable.NOTE_ENABLED;
	private ColorTable badNoteColor = ColorTable.NOTE_BAD_ENABLED;
	private ColorTable noteOnColor = ColorTable.NOTE_ON;
	private ColorTable noteOnBorder = ColorTable.NOTE_ON_BORDER;

	private boolean octaveLinesVisible = false;

	private Color[] noteColorByDynamics = new Color[Dynamics.values().length];
	private Color[] badNoteColorByDynamics = new Color[Dynamics.values().length];

	private JPanel indicatorLine;

	public NoteGraph(SequencerWrapper sequencer, TrackInfo trackInfo, int minRenderedNoteId, int maxRenderedNoteId) {
		this(sequencer, trackInfo, minRenderedNoteId, maxRenderedNoteId, 3, 2);
	}

	public NoteGraph(SequencerWrapper sequencer, TrackInfo trackInfo, int minRenderedNoteId, int maxRenderedNoteId,
			double noteWidthPx, double noteHeightPx) {

		super((LayoutManager) null);

		this.sequencer = sequencer;
		this.trackInfo = trackInfo;
		this.MIN_RENDERED = minRenderedNoteId;
		this.MAX_RENDERED = maxRenderedNoteId;
		this.NOTE_WIDTH_PX = noteWidthPx;
		this.NOTE_HEIGHT_PX = noteHeightPx;

		this.sequencer.addChangeListener(this);

		indicatorLine = new JPanel((LayoutManager) null);
		indicatorLine.setSize(1, getHeight());
		indicatorLine.setBackground(ColorTable.INDICATOR.get());
		indicatorLine.setOpaque(true);
		add(indicatorLine);

		MyMouseListener mouseListener = new MyMouseListener();
		addMouseListener(mouseListener);
		addMouseMotionListener(mouseListener);

		setOpaque(false);
		setPreferredSize(new Dimension(200, 16));

		addComponentListener(new ComponentAdapter() {
			public void componentResized(ComponentEvent e) {
				invalidateTransform();
				repositionIndicator();
			}
		});
	}

	@Override
	public void dispose() {
		sequencer.removeChangeListener(this);
	}

	protected int transposeNote(int noteId) {
		return noteId;
	}

	protected boolean isNotePlayable(int noteId) {
		return true;
	}

	protected boolean isNoteVisible(NoteEvent ne) {
		return true;
	}

	public void setOctaveLinesVisible(boolean octaveLinesVisible) {
		if (this.octaveLinesVisible != octaveLinesVisible) {
			this.octaveLinesVisible = octaveLinesVisible;
			repaint();
		}
	}

	public boolean isOctaveLinesVisible() {
		return octaveLinesVisible;
	}

	public final void setNoteColor(ColorTable noteColor) {
		if (this.noteColor != noteColor) {
			this.noteColor = noteColor;
			Arrays.fill(noteColorByDynamics, null);
			repaint();
		}
	}

	public final void setBadNoteColor(ColorTable badNoteColor) {
		if (this.badNoteColor != badNoteColor) {
			this.badNoteColor = badNoteColor;
			Arrays.fill(badNoteColorByDynamics, null);
			repaint();
		}
	}

	public final void setNoteOnColor(ColorTable noteOnColor) {
		if (this.noteOnColor != noteOnColor) {
			this.noteOnColor = noteOnColor;
			repaint();
		}
	}

	public void setTrackInfo(TrackInfo trackInfo) {
		this.trackInfo = trackInfo;
		invalidateTransform();
		repaint();
	}

	protected List<NoteEvent> getEvents() {
		return trackInfo.getEvents();
	}

	private AffineTransform noteToScreenXForm = null; // Always use getTransform()

	protected final void invalidateTransform() {
		noteToScreenXForm = null;
		repaint();
	}

	/**
	 * Gets a transform that converts song coordinates into screen coordinates.
	 */
	protected final AffineTransform getTransform() {
		if (noteToScreenXForm == null) {
			// The transform currently depends on:
			//  * This panel's width/height
			//  * The length of the sequence
			// If it changes to depend on anything else, call invalidateTransform()
			// whenever any of its dependencies changes.

			double scrnX = 0;
			double scrnY = NOTE_HEIGHT_PX;
			double scrnW = getWidth();
			double scrnH = getHeight() - NOTE_HEIGHT_PX;

			double noteX = 0;
			double noteY = MAX_RENDERED; // The max note gets mapped to 0
			double noteW = sequencer.getLength();
			double noteH = MIN_RENDERED - MAX_RENDERED;

			AffineTransform scrnXForm;
			if (noteW <= 0 || scrnW <= 0 || scrnH <= 0) {
				scrnXForm = new AffineTransform();
			}
			else {
				scrnXForm = new AffineTransform(scrnW, 0, 0, scrnH, scrnX, scrnY);
				try {
					AffineTransform noteXForm = new AffineTransform(noteW, 0, 0, noteH, noteX, noteY);
					noteXForm.invert();
					scrnXForm.concatenate(noteXForm);
				}
				catch (NoninvertibleTransformException e) {
					e.printStackTrace();
					scrnXForm.setToIdentity();
				}
			}

			noteToScreenXForm = scrnXForm;
		}

		return noteToScreenXForm;
	}

	private long lastPaintedMinSongPos = -1;
	private long lastPaintedSongPos = -1;
	private long songPos = -1;

	private boolean isShowingNotesOn() {
		return sequencer.isRunning() && sequencer.isTrackActive(trackInfo.getTrackNumber());
	}

	private void repositionIndicator() {
		AffineTransform xform = getTransform();
		Point2D.Double pt = new Point2D.Double(sequencer.getThumbPosition(), 0);
		xform.transform(pt, pt);
		indicatorLine.setBounds((int) pt.x, 0, 1, getHeight());
	}

	@Override
	public void propertyChanged(SequencerEvent evt) {
		if (evt.getProperty() == SequencerProperty.LENGTH) {
			invalidateTransform();
		}

		if (evt.getProperty().isInMask(SequencerProperty.THUMB_POSITION_MASK)) {
			repositionIndicator();
		}

		if (evt.getProperty() == SequencerProperty.IS_DRAGGING) {
			indicatorLine.setBackground(sequencer.isDragging() ? ColorTable.INDICATOR_ACTIVE.get()
					: ColorTable.INDICATOR.get());
		}

		// Repaint the parts that need it
		if (evt.getProperty() == SequencerProperty.POSITION) {
			final long currentSongPos = sequencer.getPosition();
			final long leftSongPos = Math.min(currentSongPos, Math.min(lastPaintedMinSongPos, songPos));
			final long rightSongPos = Math.max(currentSongPos, Math.max(lastPaintedSongPos, songPos))
					+ SequencerWrapper.UPDATE_FREQUENCY_MICROS;
			songPos = currentSongPos;

			if (leftSongPos < 0) {
				repaint();
			}
			else {
				AffineTransform xform = getTransform();
				long left = leftSongPos;
				long right = rightSongPos;

				// The song position changes frequently, so only repaint the rect that 
				// contains the notes that were/are playing
				if (isShowingNotesOn()) {
					for (NoteEvent ne : getEvents()) {
						if (ne.endMicros < leftSongPos)
							continue;
						if (ne.startMicros > rightSongPos)
							break;

						// This note event is or was playing
						if (ne.startMicros < left)
							left = ne.startMicros;
						if (ne.endMicros > right)
							right = ne.endMicros;
					}
				}

				// Transform to screen coordinates
				Point2D.Double pt = new Point2D.Double(left, 0);
				xform.transform(pt, pt);
				int x = (int) Math.floor(pt.x - NOTE_ON_OUTLINE_WIDTH_PX) - 2;
				pt.setLocation(right, 0);
				xform.transform(pt, pt);
				int width = (int) Math.ceil(pt.x + 2 * NOTE_ON_OUTLINE_WIDTH_PX) - x + 4;
				repaint(x, 0, width, getHeight());
			}
		}
		else {
			switch (evt.getProperty()) {
			case DRAG_POSITION:
			case IS_DRAGGING:
			case TEMPO:
				break;
			default:
				// Other properties don't change often; just repaint the whole thing
				repaint();
				break;
			}

		}
	}

	private Rectangle2D.Double rectTmp = new Rectangle2D.Double();

	private void fillNote(Graphics2D g2, NoteEvent ne, int noteId, double minWidth, double height) {
		fillNote(g2, ne, noteId, minWidth, height, 0, 0);
	}

	private void fillNote(Graphics2D g2, NoteEvent ne, int noteId, double minWidth, double height, double extraWidth,
			double extraHeight) {
		double width = Math.max(minWidth, ne.getLength());
		double y = Util.clamp(noteId, MIN_RENDERED, MAX_RENDERED);
		rectTmp.setRect(ne.startMicros - extraWidth, y - extraHeight, width + 2 * extraWidth, height + 2 * extraHeight);
		g2.fill(rectTmp);
	}

	private BitSet notesOn = null;
	private BitSet notesBad = null;

	private static float[] hsb;

	private static Color makeDynamicColor(Color base, Dynamics dyn, float weight) {
		hsb = Color.RGBtoHSB(base.getRed(), base.getGreen(), base.getBlue(), hsb);
		hsb[2] *= (1 - weight) + weight * dyn.midiVol / 128.0f;
		return new Color(Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]));
	}

	private Color getNoteColor(NoteEvent ne) {
		Dynamics dyn = Dynamics.fromMidiVelocity(ne.velocity);
		if (noteColorByDynamics[dyn.ordinal()] == null) {
			noteColorByDynamics[dyn.ordinal()] = makeDynamicColor(noteColor.get(), dyn, 0.35f);
		}
		return noteColorByDynamics[dyn.ordinal()];
	}

	@Override
	protected void paintComponent(Graphics g) {
		Graphics2D g2 = (Graphics2D) g;

		Object hintAntialiasSav = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		AffineTransform xformSav = g2.getTransform();

		AffineTransform xform = getTransform();
		double minLength = NOTE_WIDTH_PX / xform.getScaleX();
		double height = Math.abs(NOTE_HEIGHT_PX / xform.getScaleY());

		long clipPosStart = Long.MIN_VALUE;
		long clipPosEnd = Long.MAX_VALUE;

		Rectangle clipRect = g2.getClipBounds();
		if (clipRect != null) {
			// Add +/- 2 to account for antialiasing (1 would probably be enough) 
			Point2D.Double leftPoint = new Point2D.Double(clipRect.getMinX() - 2, clipRect.getMinY());
			Point2D.Double rightPoint = new Point2D.Double(clipRect.getMaxX() + 2, clipRect.getMaxY());
			try {
				xform.inverseTransform(leftPoint, leftPoint);
				xform.inverseTransform(rightPoint, rightPoint);

				clipPosStart = (long) Math.floor(Math.min(leftPoint.x, rightPoint.x));
				clipPosEnd = (long) Math.ceil(Math.max(leftPoint.x, rightPoint.x));
			}
			catch (NoninvertibleTransformException e) {
				e.printStackTrace();
			}
		}

		g2.transform(xform);

		if (octaveLinesVisible) {
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

			int minBarOctave = MIN_RENDERED / 12 + 1;
			int maxBarOctave = MAX_RENDERED / 12 - 1;
			double lineHeight = Math.abs(1 / xform.getScaleY());
			g2.setColor(ColorTable.OCTAVE_LINE.get());
			for (int barOctave = minBarOctave; barOctave <= maxBarOctave; barOctave++) {
				rectTmp.setRect(0, barOctave * 12, sequencer.getLength(), lineHeight);
				g2.fill(rectTmp);
			}
		}

		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		boolean showNotesOn = isShowingNotesOn() && songPos >= 0;
		long minSongPos = songPos;

		if (showNotesOn) {
			// Highlight all notes that are on, or were on since we last painted (up to 100ms ago) 
			if (lastPaintedSongPos >= 0 && lastPaintedSongPos < songPos) {
				minSongPos = Math.max(lastPaintedSongPos, songPos - 4 * SequencerWrapper.UPDATE_FREQUENCY_MICROS);
			}
		}

		lastPaintedMinSongPos = minSongPos;
		lastPaintedSongPos = songPos;

		List<NoteEvent> noteEvents = getEvents();

		if (notesOn != null)
			notesOn.clear();
		if (notesBad != null)
			notesBad.clear();

		// Paint the playable notes and keep track of the currently sounding and unplayable notes
		g2.setColor(noteColor.get());
		for (int i = 0; i < noteEvents.size(); i++) {
			NoteEvent ne = noteEvents.get(i);

			// Don't bother drawing the note if it's clipped
			if (ne.endMicros < clipPosStart || ne.startMicros > clipPosEnd)
				continue;

			if (isNoteVisible(ne)) {
				int noteId = transposeNote(ne.note.id);

				if (showNotesOn && songPos >= ne.startMicros && minSongPos <= ne.endMicros
						&& sequencer.isNoteActive(ne.note.id)) {
					if (notesOn == null)
						notesOn = new BitSet(noteEvents.size());
					notesOn.set(i);
				}
				else if (!isNotePlayable(noteId)) {
					if (notesBad == null)
						notesBad = new BitSet(noteEvents.size());
					notesBad.set(i);
				}
				else {
					g2.setColor(getNoteColor(ne));
					fillNote(g2, ne, noteId, minLength, height);
				}
			}
		}

		if (notesBad != null) {
			g2.setColor(badNoteColor.get());
			for (int i = notesBad.nextSetBit(0); i >= 0; i = notesBad.nextSetBit(i + 1)) {
				NoteEvent ne = noteEvents.get(i);
				int noteId = transposeNote(ne.note.id);
				fillNote(g2, ne, noteId, minLength, height);
			}
		}

		if (notesOn != null) {
			double noteOnOutlineWidthX = NOTE_ON_OUTLINE_WIDTH_PX / xform.getScaleX();
			double noteOnOutlineWidthY = Math.abs(NOTE_ON_OUTLINE_WIDTH_PX / xform.getScaleY());
			double noteOnExtraHeightY = Math.abs(NOTE_ON_EXTRA_HEIGHT_PX / xform.getScaleY());

			g2.setColor(noteOnBorder.get());
			for (int i = notesOn.nextSetBit(0); i >= 0; i = notesOn.nextSetBit(i + 1)) {
				NoteEvent ne = noteEvents.get(i);
				int noteId = transposeNote(ne.note.id);

				fillNote(g2, noteEvents.get(i), noteId, minLength, height, noteOnOutlineWidthX, noteOnExtraHeightY
						+ noteOnOutlineWidthY);
			}

			g2.setColor(noteOnColor.get());
			for (int i = notesOn.nextSetBit(0); i >= 0; i = notesOn.nextSetBit(i + 1)) {
				NoteEvent ne = noteEvents.get(i);
				int noteId = transposeNote(ne.note.id);

				fillNote(g2, noteEvents.get(i), noteId, minLength, height, 0, noteOnExtraHeightY);
			}
		}

		g2.setTransform(xformSav);
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, hintAntialiasSav);
	}

	private class MyMouseListener extends MouseAdapter {
		private long positionFromEvent(MouseEvent e) {
			AffineTransform xform = getTransform();
			Point2D.Double pt = new Point2D.Double(e.getX(), e.getY());
			try {
				xform.inverseTransform(pt, pt);
				long ret = (long) pt.x;
				if (ret < 0)
					ret = 0;
				if (ret >= sequencer.getLength())
					ret = sequencer.getLength() - 1;
				return ret;
			}
			catch (NoninvertibleTransformException e1) {
				e1.printStackTrace();
				return 0;
			}
		}

		private boolean isDragCanceled(MouseEvent e) {
			// Allow drag to continue anywhere within the scroll pane
			Component dragArea = SwingUtilities.getAncestorOfClass(JScrollPane.class, NoteGraph.this);
			if (dragArea == null)
				dragArea = NoteGraph.this;

			Point pt = SwingUtilities.convertPoint(NoteGraph.this, e.getPoint(), dragArea);
			return (pt.x < -32 || pt.x > dragArea.getWidth() + 32) || (pt.y < -32 || pt.y > dragArea.getHeight() + 32);
		}

		public void mousePressed(MouseEvent e) {
			if (e.getButton() == MouseEvent.BUTTON1) {
				sequencer.setDragging(true);
				sequencer.setDragPosition(positionFromEvent(e));
			}
		}

		public void mouseDragged(MouseEvent e) {
			if ((e.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) != 0) {
				if (!isDragCanceled(e)) {
					sequencer.setDragging(true);
					sequencer.setDragPosition(positionFromEvent(e));
				}
				else {
					sequencer.setDragging(false);
				}
			}
		}

		public void mouseReleased(MouseEvent e) {
			if (e.getButton() == MouseEvent.BUTTON1) {
				sequencer.setDragging(false);
				if (!isDragCanceled(e)) {
					sequencer.setPosition(positionFromEvent(e));
				}
			}
		}
	}
}