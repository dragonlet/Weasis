package org.weasis.base.explorer.list.impl;

import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.base.explorer.Messages;
import org.weasis.base.explorer.list.AThumbnailList;
import org.weasis.base.explorer.list.IThumbnailList;
import org.weasis.base.explorer.list.IThumbnailModel;
import org.weasis.core.api.media.data.MediaElement;
import org.weasis.core.ui.util.TitleMenuItem;

@SuppressWarnings("serial")
public final class JIThumbnailList<E extends MediaElement<?>> extends AThumbnailList<E> implements IThumbnailList<E> {
    private static final Logger LOGGER = LoggerFactory.getLogger(JIThumbnailList.class);

    public JIThumbnailList() {
        super();
    }

    @Override
    public IThumbnailModel<E> newModel() {
        return new JIListModel<>(this);
    }

    @Override
    public JPopupMenu buidContexMenu(final MouseEvent e) {

        try {
            final List<E> selMedias = getSelected(e);
            if (selMedias.isEmpty()) {
                return null;
            } else {
                JPopupMenu popupMenu = new JPopupMenu();
                TitleMenuItem itemTitle =
                    new TitleMenuItem(Messages.getString("JIThumbnailList.sel_menu"), popupMenu.getInsets()); //$NON-NLS-1$
                popupMenu.add(itemTitle);
                popupMenu.addSeparator();

                if (selMedias.size() == 1) {
                    JMenuItem menuItem = new JMenuItem(new AbstractAction(Messages.getString("JIThumbnailList.open")) { //$NON-NLS-1$

                        @Override
                        public void actionPerformed(final ActionEvent e) {
                            openSelection(selMedias, true, true, false);
                        }
                    });
                    popupMenu.add(menuItem);

                    menuItem = new JMenuItem(new AbstractAction(Messages.getString("JIThumbnailList.open_win")) { //$NON-NLS-1$

                        @Override
                        public void actionPerformed(final ActionEvent e) {
                            openSelection(selMedias, false, true, false);
                        }
                    });

                    popupMenu.add(menuItem);

                    menuItem = new JMenuItem(new AbstractAction(Messages.getString("JIThumbnailList.open_sel_win")) { //$NON-NLS-1$

                        @Override
                        public void actionPerformed(final ActionEvent e) {
                            openSelection(selMedias, true, false, true);
                        }
                    });
                    popupMenu.add(menuItem);
                } else {
                    JMenu menu = new JMenu(Messages.getString("JIThumbnailList.open_win")); //$NON-NLS-1$
                    JMenuItem menuItem =
                        new JMenuItem(new AbstractAction(Messages.getString("JIThumbnailList.stack_mode")) { //$NON-NLS-1$

                            @Override
                            public void actionPerformed(final ActionEvent e) {
                                openGroup(selMedias, false, true, false, false);
                            }

                        });
                    menu.add(menuItem);
                    menuItem = new JMenuItem(new AbstractAction(Messages.getString("JIThumbnailList.layout_mode")) { //$NON-NLS-1$

                        @Override
                        public void actionPerformed(final ActionEvent e) {
                            openGroup(selMedias, false, true, true, false);
                        }

                    });
                    menu.add(menuItem);
                    popupMenu.add(menu);

                    menu = new JMenu(Messages.getString("JIThumbnailList.add_to_win")); //$NON-NLS-1$
                    menuItem = new JMenuItem(new AbstractAction(Messages.getString("JIThumbnailList.stack_mode")) { //$NON-NLS-1$

                        @Override
                        public void actionPerformed(final ActionEvent e) {
                            openGroup(selMedias, true, false, false, true);
                        }

                    });
                    menu.add(menuItem);
                    menuItem = new JMenuItem(new AbstractAction(Messages.getString("JIThumbnailList.layout_mode")) { //$NON-NLS-1$

                        @Override
                        public void actionPerformed(final ActionEvent e) {
                            openGroup(selMedias, true, false, true, true);
                        }

                    });
                    menu.add(menuItem);
                    popupMenu.add(menu);

                }
                return popupMenu;

            }
        } catch (Exception ex) {
            LOGGER.error("Build context menu", ex);
        } finally {
            e.consume();
        }
        return null;

    }

    @Override
    public void mouseClickedEvent(MouseEvent e) {
        if (e.getClickCount() == 2) {
            openSelection();
        }
    }

}
