/*******************************************************************************
 * Copyright (c) 2011 Weasis Team.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Nicolas Roduit - initial API and implementation
 ******************************************************************************/
package org.weasis.core.ui.model.graphic;

import java.util.List;

import org.weasis.core.api.image.util.MeasurableLayer;
import org.weasis.core.ui.model.utils.bean.MeasureItem;

public interface GraphicSelectionListener {

    void handle(List<Graphic> selectedGraphics, MeasurableLayer layer);

    void updateMeasuredItems(List<MeasureItem> measureList);
}
