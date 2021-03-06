/*******************************************************************************
 * Copyright (c) 2015 Weasis Team.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Nicolas Roduit - initial API and implementation
 ******************************************************************************/
package org.weasis.jpeg.internal;

import java.nio.ByteBuffer;

import org.bytedeco.javacpp.IntPointer;
import org.weasis.jpeg.cpp.libijg.RETURN_MSG;

public interface EncoderIJG {

    int bytesPerSample();

    int bitsPerSample();

    void deallocate();

    RETURN_MSG encode(int columns, int rows, int interpr, int samplesPerPixel, ByteBuffer image_buffer, ByteBuffer to,
        IntPointer length);
}
