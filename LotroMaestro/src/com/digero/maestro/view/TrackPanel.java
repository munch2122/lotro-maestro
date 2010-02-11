package com.digero.maestro.view;

import info.clearthought.layout.TableLayout;
import info.clearthought.layout.TableLayoutConstants;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sound.midi.Sequence;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import com.digero.maestro.abc.AbcPart;
import com.digero.maestro.midi.Note;
import com.digero.maestro.midi.NoteEvent;
import com.digero.maestro.midi.SequencerEvent;
import com.digero.maestro.midi.SequencerListener;
import com.digero.maestro.midi.SequencerWrapper;
import com.digero.maestro.midi.TrackInfo;

@SuppressWarnings("serial")
public class TrackPanel extends JPanel implements TableLayoutConstants {
	//              0              1               2
	//   +--------------------+----------+--------------------+
	//   |      TRACK NAME    | octave   |  +--------------+  |
	// 0 | [X]                | +----^-+ |  | (note graph) |  |
	//   |      Instrument(s) | +----v-+ |  +--------------+  |
	//   +--------------------+----------+--------------------+
	private static final int TITLE_WIDTH = 160;
	private static final double[] LAYOUT_COLS = new double[] {
			TITLE_WIDTH, 48, FILL, 1
	};
	private static final double[] LAYOUT_ROWS = new double[] {
			4, 48, 4
	};

	private ProjectFrame project;
	private TrackInfo trackInfo;
	private SequencerWrapper seq;
	private AbcPart abcPart;

	private JCheckBox checkBox;
	private JSpinner transposeSpinner;
	private NoteGraph noteGraph;

	public TrackPanel(ProjectFrame project, TrackInfo info, SequencerWrapper sequencer, AbcPart part) {
		super(new TableLayout(LAYOUT_COLS, LAYOUT_ROWS));
		setBackground(Color.WHITE);
		setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.DARK_GRAY));
//		setBorder(BorderFactory.createBevelBorder(BevelBorder.RAISED, Color.LIGHT_GRAY, Color.GRAY));

		this.project = project;
		this.trackInfo = info;
		this.seq = sequencer;
		this.abcPart = part;

		TableLayout tableLayout = (TableLayout) getLayout();
		tableLayout.setHGap(4);

		checkBox = new JCheckBox();
		checkBox.setOpaque(false);
		checkBox.setSelected(abcPart.isTrackEnabled(trackInfo.getTrackNumber()));

		String title = trackInfo.getTrackNumber() + ". " + trackInfo.getName();
		String instr = trackInfo.getInstrumentNames();
		checkBox.setToolTipText("<html><b>" + title + "</b><br>" + instr + "</html>");

		title = ellipsis(title, TITLE_WIDTH - 32, checkBox.getFont().deriveFont(Font.BOLD));
		instr = ellipsis(instr, TITLE_WIDTH - 32, checkBox.getFont());
		checkBox.setText("<html><b>" + title + "</b><br>" + instr + "</html>");

		checkBox.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				int track = trackInfo.getTrackNumber();
				boolean enabled = checkBox.isSelected();
				abcPart.setTrackEnabled(track, enabled);
				seq.setTrackMute(track, !enabled, TrackPanel.this);
			}
		});

		JLabel octaveLabel = new JLabel("octave", SwingConstants.RIGHT);
		octaveLabel.setOpaque(false);
		octaveLabel.setFont(octaveLabel.getFont().deriveFont(Font.ITALIC));

		int currentTranspose = abcPart.getTrackTranspose(trackInfo.getTrackNumber());
		transposeSpinner = new JSpinner(new TrackTransposeModel(currentTranspose, -48, 48, 12));
		transposeSpinner.setToolTipText("Transpose this track by octaves (12 semitones)");

		transposeSpinner.addChangeListener(new ChangeListener() {
			public void stateChanged(ChangeEvent e) {
				int track = trackInfo.getTrackNumber();
				int value = (Integer) transposeSpinner.getValue();
				if (value % 12 != 0) {
					value = (abcPart.getTrackTranspose(track) / 12) * 12;
					transposeSpinner.setValue(value);
				}
				else {
					abcPart.setTrackTranspose(trackInfo.getTrackNumber(), value);
				}
			}
		});

		noteGraph = new NoteGraph();
		noteGraph.setOpaque(false);

		add(checkBox, "0, 1");
//		add(octaveLabel, "1, 1, f, b");
		add(transposeSpinner, "1, 1, f, c");
		add(noteGraph, "2, 1");
	}

	private class TrackTransposeModel extends SpinnerNumberModel {
		public TrackTransposeModel(int value, int minimum, int maximum, int stepSize) {
			super(value, minimum, maximum, stepSize);
		}

		@Override
		public void setValue(Object value) {
			if (!(value instanceof Integer))
				throw new IllegalArgumentException();

			if ((Integer) value % 12 != 0)
				throw new IllegalArgumentException();

			super.setValue(value);
		}
	}

	public static final String ELLIPSIS = "...";

	@SuppressWarnings("deprecation")
	public static String ellipsis(String text, float maxWidth, Font font) {
		FontMetrics metrics = Toolkit.getDefaultToolkit().getFontMetrics(font);
		Pattern prevWord = Pattern.compile("\\w*\\W*$");
		Matcher matcher = prevWord.matcher(text);

		float width = metrics.stringWidth(text);
		if (width < maxWidth)
			return text;

		int len = 0;
		int seg = text.length();
		String fit = "";

		// find the longest string that fits into
		// the control boundaries using bisection method 
		while (seg > 1) {
			seg -= seg / 2;

			int left = len + seg;
			int right = text.length();

			if (left > right)
				continue;

			// trim at a word boundary using regular expressions 
			matcher.region(0, left);
			if (matcher.find())
				left = matcher.start();

			// build and measure a candidate string with ellipsis
			String tst = text.substring(0, left) + ELLIPSIS;

			width = metrics.stringWidth(tst);

			// candidate string fits into boundaries, try a longer string
			// stop when seg <= 1
			if (width <= maxWidth) {
				len += seg;
				fit = tst;
			}
		}

		// string can't fit
		if (len == 0)
			return ELLIPSIS;

		return fit;
	}

	private static Color grayscale(Color orig) {
		float[] hsb = Color.RGBtoHSB(orig.getRed(), orig.getGreen(), orig.getBlue(), null);
		return Color.getHSBColor(0.0f, 0.0f, hsb[2]);
	}

	private static final Color NOTE = new Color(29, 95, 255);
	private static final Color XNOTE = Color.RED;
	private static final Color NOTE_DISABLED = grayscale(NOTE).darker(); // Color.GRAY;
	private static final Color XNOTE_DISABLED = grayscale(XNOTE); // Color.LIGHT_GRAY;
	private static final Color NOTE_ON = Color.WHITE;
	private static final Color BKGD_COLOR = Color.BLACK;
	private static final Color BKGD_DISABLED = Color.DARK_GRAY.darker();
	private static final Color BORDER_COLOR = Color.DARK_GRAY;
	private static final Color BORDER_DISABLED = Color.DARK_GRAY;

	private static final Color INDICATOR_COLOR = new Color(0xAAFFFFFF, true);

	private static final int MIN_RENDERED = Note.C2.id - 12;
	private static final int MAX_RENDERED = Note.C5.id + 12;

	private class NoteGraph extends JPanel {
		private static final int BORDER_SIZE = 2;
		private static final double NOTE_WIDTH_PX = 3;
		private static final double NOTE_HEIGHT_PX = 2;

		public NoteGraph() {
			abcPart.addChangeListener(myChangeListener);
			seq.addChangeListener(myChangeListener);

			MyMouseListener mouseListener = new MyMouseListener();
			addMouseListener(mouseListener);
			addMouseMotionListener(mouseListener);
			setPreferredSize(new Dimension(200, 24));
		}

		private class MyChangeListener implements ChangeListener, SequencerListener {
			public void stateChanged(ChangeEvent e) {
				repaint();
			}

			public void propertyChanged(SequencerEvent evt) {
				repaint();
			}
		}

		private MyChangeListener myChangeListener = new MyChangeListener();

		private Rectangle2D.Double rectTmp = new Rectangle2D.Double();
		private Line2D.Double lineTmp = new Line2D.Double();

		@Override
		public void paint(Graphics g) {
			super.paint(g);

			Sequence song = trackInfo.getSequenceInfo().getSequence();
			if (song == null)
				return;

			Graphics2D g2 = (Graphics2D) g;
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

			AffineTransform xform = getTransform();
			double minLength = NOTE_WIDTH_PX / xform.getScaleX();
			double height = Math.abs(NOTE_HEIGHT_PX / xform.getScaleY());

			boolean trackEnabled = seq.isTrackActive(trackInfo.getTrackNumber());
			long songPos = seq.getPosition();
			int transpose = abcPart.getTrackTranspose(trackInfo.getTrackNumber()) + abcPart.getBaseTranspose();
			int minPlayable = abcPart.getInstrument().lowestPlayable.id;
			int maxPlayable = abcPart.getInstrument().highestPlayable.id;

			g2.setColor(trackEnabled ? BORDER_COLOR : BORDER_DISABLED);
			g2.fillRoundRect(0, 0, getWidth(), getHeight(), 5, 5);
			g2.setColor(trackEnabled ? BKGD_COLOR : BKGD_DISABLED);
			g2.fillRoundRect(BORDER_SIZE, BORDER_SIZE, getWidth() - 2 * BORDER_SIZE, getHeight() - 2 * BORDER_SIZE, 5,
					5);

			g2.transform(xform);

			List<Rectangle2D> notesUnplayable = new ArrayList<Rectangle2D>();
			List<Rectangle2D> notesPlaying = null;
			if (trackEnabled && seq.isRunning())
				notesPlaying = new ArrayList<Rectangle2D>();

			// Paint the playable notes and keep track of the currently sounding and unplayable notes
			g2.setColor(trackEnabled ? NOTE : NOTE_DISABLED);
			for (NoteEvent evt : trackInfo.getNoteEvents()) {
				int id = evt.note.id + transpose;
				double width = Math.max(minLength, evt.getLength());
				double y;
				boolean playable;

				if (id < minPlayable) {
					y = Math.max(id, MIN_RENDERED);
					playable = false;
				}
				else if (id > maxPlayable) {
					y = Math.min(id, MAX_RENDERED);
					playable = false;
				}
				else {
					y = id;
					playable = true;
				}

				if (notesPlaying != null && songPos >= evt.startMicros && songPos <= evt.endMicros) {
					notesPlaying.add(new Rectangle2D.Double(evt.startMicros, y, width, height));
				}
				else if (!playable) {
					notesUnplayable.add(new Rectangle2D.Double(evt.startMicros, y, width, height));
				}
				else {
					rectTmp.setRect(evt.startMicros, y, width, height);
					g2.fill(rectTmp);
				}
			}

			// Paint the unplayable notes above the playable ones
			g2.setColor(trackEnabled ? XNOTE : XNOTE_DISABLED);
			for (Rectangle2D rect : notesUnplayable) {
				g2.fill(rect);
			}

			// Paint the currently playing notes last
			if (notesPlaying != null) {
				g2.setColor(NOTE_ON);
				for (Rectangle2D rect : notesPlaying) {
					g2.fill(rect);
				}
			}

			// Draw the indicator line
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
			g2.setColor(INDICATOR_COLOR);
			long thumbPos = seq.getThumbPosition();
			lineTmp.setLine(thumbPos, MIN_RENDERED, thumbPos, MAX_RENDERED + height);
			g2.draw(lineTmp);
		}

		/**
		 * Gets a transform that converts song coordinates into screen
		 * coordinates.
		 */
		private AffineTransform getTransform() {
			Sequence song = trackInfo.getSequenceInfo().getSequence();
			if (song == null)
				return new AffineTransform();

			double scrnX = BORDER_SIZE;
			double scrnY = BORDER_SIZE + NOTE_HEIGHT_PX;
			double scrnW = getWidth() - 2 * BORDER_SIZE;
			double scrnH = getHeight() - 2 * BORDER_SIZE - NOTE_HEIGHT_PX;

			double noteX = 0;
			double noteY = MAX_RENDERED;
			double noteW = song.getMicrosecondLength();
			double noteH = MIN_RENDERED - MAX_RENDERED;

			if (noteW <= 0 || scrnW <= 0 || scrnH <= 0)
				return new AffineTransform();

			AffineTransform scrnXForm = new AffineTransform(scrnW, 0, 0, scrnH, scrnX, scrnY);
			AffineTransform noteXForm = new AffineTransform(noteW, 0, 0, noteH, noteX, noteY);
			try {
				noteXForm.invert();
			}
			catch (NoninvertibleTransformException e) {
				e.printStackTrace();
				return new AffineTransform();
			}
			scrnXForm.concatenate(noteXForm);

			return scrnXForm;
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
					if (ret >= seq.getLength())
						ret = seq.getLength() - 1;
					return ret;
				}
				catch (NoninvertibleTransformException e1) {
					e1.printStackTrace();
					return 0;
				}
			}

			private boolean isDragCanceled(MouseEvent e) {
				int x = e.getX();
				if (x < -32 || x > getWidth() + 32)
					return true;

				Component ancestor = SwingUtilities.getAncestorOfClass(JScrollPane.class, NoteGraph.this);
				if (ancestor == null)
					ancestor = SwingUtilities.getRoot(NoteGraph.this);

				int y = SwingUtilities.convertPoint(NoteGraph.this, e.getPoint(), ancestor).y;
				int h = ancestor.getHeight();
				return y < -32 || y > h + 32;
			}

			public void mousePressed(MouseEvent e) {
				if (e.getButton() == MouseEvent.BUTTON1) {
					seq.setDragging(true, this);
					seq.setDragPosition(positionFromEvent(e), this);
				}
			}

			public void mouseDragged(MouseEvent e) {
				if ((e.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) != 0) {
					if (!isDragCanceled(e)) {
						seq.setDragging(true, this);
						seq.setDragPosition(positionFromEvent(e), this);
					}
					else {
						seq.setDragging(false, this);
					}
				}
			}

			public void mouseReleased(MouseEvent e) {
				if (e.getButton() == MouseEvent.BUTTON1) {
					seq.setDragging(false, this);
					if (!isDragCanceled(e)) {
						seq.setPosition(positionFromEvent(e), this);
					}
				}
			}
		}
	}
}
