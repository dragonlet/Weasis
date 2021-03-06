/*******************************************************************************
 * Copyright (c) 2010 Nicolas Roduit.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Nicolas Roduit - initial API and implementation
 ******************************************************************************/
package org.weasis.core.ui.model.layer;

import org.weasis.core.ui.model.GraphicModel;

/**
 * The listener interface for receiving layerModelChange events.
 */
public interface GraphicModelChangeListener {

    default void handleModelChanged(GraphicModel modelList) {
    }

    default void handleLayerAdded(GraphicModel modelList, Layer layer) {
    }

    default void handleLayerRemoved(GraphicModel modelList, Layer layer) {
    }

    default void handleLayerChanged(GraphicModel modelList, Layer layer) {
    }
}
