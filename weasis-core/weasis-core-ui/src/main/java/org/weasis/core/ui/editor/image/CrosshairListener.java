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
package org.weasis.core.ui.editor.image;

import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.util.Objects;
import java.util.Optional;

import org.weasis.core.api.gui.util.ActionState;
import org.weasis.core.api.gui.util.ActionW;
import org.weasis.core.api.gui.util.BasicActionState;
import org.weasis.core.api.gui.util.MouseActionAdapter;
import org.weasis.core.api.service.AuditLog;

public abstract class CrosshairListener extends MouseActionAdapter implements ActionState, KeyListener {

    private final BasicActionState basicState;
    private final boolean triggerAction = true;
    private Point pickPoint;

    private Point2D point;

    public CrosshairListener(ActionW action, Point2D point) {
        this.basicState = new BasicActionState(action);
        this.point = point == null ? new Point2D.Double() : point;
    }

    @Override
    public void enableAction(boolean enabled) {
        basicState.enableAction(enabled);
    }

    @Override
    public boolean isActionEnabled() {
        return basicState.isActionEnabled();
    }

    @Override
    public boolean registerActionState(Object c) {
        return basicState.registerActionState(c);
    }

    @Override
    public void unregisterActionState(Object c) {
        basicState.unregisterActionState(c);
    }

    public Point2D getPoint() {
        return (Point2D) point.clone();
    }

    public void setPoint(Point2D point) {
        if (point != null) {
            this.point = point;
            pointChanged(point);
            AuditLog.LOGGER.info("action:{} val:{},{}", //$NON-NLS-1$
                new Object[] { basicState.getActionW().cmd(), point.getX(), point.getY() });
        }
    }

    public boolean isTriggerAction() {
        return triggerAction;
    }

    @Override
    public ActionW getActionW() {
        return basicState.getActionW();
    }

    public String getValueToDisplay() {
        return "x:" + point.getX() + ", y:" + point.getY(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public abstract void pointChanged(Point2D point);

    @Override
    public void setButtonMaskEx(int buttonMask) {
        // Zero is used to disable the mouse adapter
        if (buttonMask == 0 && this.buttonMaskEx != 0) {
            // Convention to delete cross-lines on the views when selecting another action
            this.setPoint(new Point2D.Double(Double.NaN, Double.NaN));
        }
        super.setButtonMaskEx(buttonMask);
    }

    @Override
    public String toString() {
        return basicState.getActionW().getTitle();
    }

    private ViewCanvas<?> getIViewCanvas(InputEvent e) {
        Object source = e.getSource();
        if (source instanceof ViewCanvas) {
            return (ViewCanvas<?>) source;
        }
        return null;
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (basicState.isActionEnabled()) {
            int buttonMask = getButtonMaskEx();
            if ((e.getModifiersEx() & buttonMask) != 0) {
                ViewCanvas<?> panner = getIViewCanvas(e);
                if (Objects.nonNull(panner)) {
                    pickPoint = e.getPoint();
                    setPoint(panner.getImageCoordinatesFromMouse(e.getX(), e.getY()));
                }
            }
        }
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (basicState.isActionEnabled()) {
            int buttonMask = getButtonMaskEx();
            if ((e.getModifiersEx() & buttonMask) != 0) {
                ViewCanvas<?> panner = getIViewCanvas(e);
                if (Objects.nonNull(panner) && Objects.nonNull(pickPoint)) {
                    setPoint(panner.getImageCoordinatesFromMouse(e.getX(), e.getY()));
                }
            }
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (basicState.isActionEnabled()) {
            int buttonMask = getButtonMask();
            if ((e.getModifiers() & buttonMask) != 0) {
                ViewCanvas<?> panner = getIViewCanvas(e);
                Optional.ofNullable(panner).ifPresent(p -> p.getJComponent().repaint());
            }
        }
    }

    public void reset() {
        pickPoint = null;
    }

    @Override
    public void keyPressed(KeyEvent e) {
    }

    @Override
    public void keyReleased(KeyEvent e) {
    }

    @Override
    public void keyTyped(KeyEvent e) {
    }

}
