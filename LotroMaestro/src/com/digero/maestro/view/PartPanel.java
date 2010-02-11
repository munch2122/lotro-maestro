package com.digero.maestro.view;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import sun.awt.VerticalBagLayout;

import com.digero.maestro.abc.AbcPart;
import com.digero.maestro.abc.LotroInstrument;
import com.digero.maestro.midi.SequencerWrapper;
import com.digero.maestro.midi.TrackInfo;

@SuppressWarnings("serial")
public class PartPanel extends JPanel {
	private static final int HGAP = 4, VGAP = 4;

	private ProjectFrame project;
	private AbcPart abcPart;
	private SequencerWrapper sequencer;

	private JSpinner numberSpinner;
	private JTextField nameTextField;
	private JComboBox instrumentComboBox;

	private JScrollPane trackScrollPane;

	private JPanel trackListPanel;

	public PartPanel(ProjectFrame project, SequencerWrapper sequencer) {
		super(new BorderLayout(HGAP, VGAP));

		this.project = project;

		this.sequencer = sequencer;

		numberSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 9999, 1));
		numberSpinner.addChangeListener(new ChangeListener() {
			public void stateChanged(ChangeEvent e) {
				if (abcPart != null)
					abcPart.setPartNumber((Integer) numberSpinner.getValue());
			}
		});

		nameTextField = new JTextField(32);
		nameTextField.getDocument().addDocumentListener(new DocumentListener() {
			public void removeUpdate(DocumentEvent e) {
				if (abcPart != null)
					abcPart.setTitle(nameTextField.getText());
			}

			public void insertUpdate(DocumentEvent e) {
				if (abcPart != null)
					abcPart.setTitle(nameTextField.getText());
			}

			public void changedUpdate(DocumentEvent e) {
				if (abcPart != null)
					abcPart.setTitle(nameTextField.getText());
			}
		});

		instrumentComboBox = new JComboBox(LotroInstrument.NON_DRUMS);
		instrumentComboBox.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if (abcPart != null)
					abcPart.setInstrument((LotroInstrument) instrumentComboBox.getSelectedItem());
			}
		});

		JPanel dataPanel = new JPanel(new BorderLayout(HGAP, VGAP));
		dataPanel.add(numberSpinner, BorderLayout.WEST);
		dataPanel.add(nameTextField, BorderLayout.CENTER);
		dataPanel.add(instrumentComboBox, BorderLayout.EAST);

		trackListPanel = new JPanel(new VerticalBagLayout());
		trackListPanel.setBackground(Color.WHITE);

		trackScrollPane = new JScrollPane(trackListPanel, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
				JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

		add(dataPanel, BorderLayout.NORTH);
		add(trackScrollPane, BorderLayout.CENTER);

		setAbcPart(null);
	}

	private ChangeListener partChangeListener = new ChangeListener() {
		public void stateChanged(ChangeEvent e) {
		}
	};

	public void setAbcPart(AbcPart abcPart) {
		if (this.abcPart == abcPart)
			return;

		if (this.abcPart != null) {
			this.abcPart.removeChangeListener(partChangeListener);
			this.abcPart = null;
		}

		if (abcPart == null) {
			numberSpinner.setEnabled(false);
			nameTextField.setEnabled(false);
			instrumentComboBox.setEnabled(false);

			numberSpinner.setValue(0);
			nameTextField.setText("");
			instrumentComboBox.setSelectedIndex(0);

			trackListPanel.removeAll();
		}
		else {
			numberSpinner.setEnabled(true);
			nameTextField.setEnabled(true);
			instrumentComboBox.setEnabled(true);

			numberSpinner.setValue(abcPart.getPartNumber());
			nameTextField.setText(abcPart.getTitle());
			instrumentComboBox.setSelectedItem(abcPart.getInstrument());

			trackListPanel.removeAll();
			boolean gray = false;
			for (TrackInfo track : abcPart.getSequenceInfo().getTrackList()) {
				int trackNumber = track.getTrackNumber();
				if (track.hasNotes()) {
					TrackPanel trackPanel = new TrackPanel(project, track, sequencer, abcPart);
					trackPanel.setBackground(gray ? Color.LIGHT_GRAY : Color.WHITE);
					gray = !gray;
					trackScrollPane.getVerticalScrollBar().setUnitIncrement(trackPanel.getPreferredSize().height);
					trackListPanel.add(trackPanel);
				}
				sequencer.setTrackMute(trackNumber, !abcPart.isTrackEnabled(trackNumber), this);
				sequencer.setTrackSolo(trackNumber, false, this);
			}

		}

		this.abcPart = abcPart;
		if (this.abcPart != null) {
			this.abcPart.addChangeListener(partChangeListener);
		}

		validate();
		repaint();
	}
}
