/* Copyright (c) 2010 Ben Howell
 * This software is licensed under the MIT License
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a 
 * copy of this software and associated documentation files (the "Software"), 
 * to deal in the Software without restriction, including without limitation 
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, 
 * and/or sell copies of the Software, and to permit persons to whom the 
 * Software is furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in 
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR 
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, 
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE 
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER 
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING 
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER 
 * DEALINGS IN THE SOFTWARE.
 */

package com.digero.maestro.midi;


public class NoteEvent implements Comparable<NoteEvent> {
	public long startMicros;
	public long endMicros;
	public Note note;

	public NoteEvent(Note note, long startMicros) {
		this.note = note;
		this.startMicros = startMicros;
		this.endMicros = startMicros;
	}

	public NoteEvent(Note note, long startMicros, long endMicros) {
		this.note = note;
		this.startMicros = startMicros;
		this.endMicros = endMicros;
	}

	public long getLength() {
		return endMicros - startMicros;
	}

	public void setLength(long length) {
		endMicros = startMicros + length;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof NoteEvent) {
			NoteEvent that = (NoteEvent) obj;
			return (this.startMicros == that.startMicros) && (this.endMicros == that.endMicros)
					&& (this.note.id == that.note.id);
		}
		return false;
	}

	@Override
	public int hashCode() {
		return ((int) startMicros) ^ ((int) endMicros) ^ note.id;
	}

	@Override
	public int compareTo(NoteEvent that) {
		if (that == null)
			return 1;

		if (this.startMicros != that.startMicros)
			return (this.startMicros > that.startMicros) ? 1 : -1;

		if (this.note.id != that.note.id)
			return this.note.id - that.note.id;

		if (this.endMicros != that.endMicros)
			return (this.endMicros > that.endMicros) ? 1 : -1;

		return 0;
	}
}