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
package org.weasis.dicom.explorer;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.io.IOException;
import java.text.Collator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;

import org.dcm4che3.data.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.command.Option;
import org.weasis.core.api.command.Options;
import org.weasis.core.api.explorer.ObservableEvent;
import org.weasis.core.api.explorer.model.DataExplorerModel;
import org.weasis.core.api.explorer.model.Tree;
import org.weasis.core.api.explorer.model.TreeModel;
import org.weasis.core.api.explorer.model.TreeModelNode;
import org.weasis.core.api.gui.util.AppProperties;
import org.weasis.core.api.gui.util.GuiExecutor;
import org.weasis.core.api.media.data.Codec;
import org.weasis.core.api.media.data.MediaElement;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.media.data.MediaSeriesGroup;
import org.weasis.core.api.media.data.MediaSeriesGroupNode;
import org.weasis.core.api.media.data.Series;
import org.weasis.core.api.media.data.TagView;
import org.weasis.core.api.media.data.TagW;
import org.weasis.core.api.media.data.Thumbnail;
import org.weasis.core.api.service.BundleTools;
import org.weasis.core.api.util.GzipManager;
import org.weasis.core.api.util.ThreadUtil;
import org.weasis.core.ui.docking.UIManager;
import org.weasis.core.ui.editor.SeriesViewerFactory;
import org.weasis.core.ui.editor.ViewerPluginBuilder;
import org.weasis.dicom.codec.DicomEncapDocElement;
import org.weasis.dicom.codec.DicomEncapDocSeries;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.DicomMediaIO;
import org.weasis.dicom.codec.DicomSeries;
import org.weasis.dicom.codec.DicomSpecialElement;
import org.weasis.dicom.codec.DicomVideoElement;
import org.weasis.dicom.codec.DicomVideoSeries;
import org.weasis.dicom.codec.KOSpecialElement;
import org.weasis.dicom.codec.PRSpecialElement;
import org.weasis.dicom.codec.RejectedKOSpecialElement;
import org.weasis.dicom.codec.SortSeriesStack;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.codec.display.Modality;
import org.weasis.dicom.codec.utils.SplittingModalityRules;
import org.weasis.dicom.codec.utils.SplittingModalityRules.Rule;
import org.weasis.dicom.codec.utils.SplittingRules;
import org.weasis.dicom.explorer.DicomExplorer.SeriesPane;
import org.weasis.dicom.explorer.DicomExplorer.StudyPane;
import org.weasis.dicom.explorer.wado.DicomManager;
import org.weasis.dicom.explorer.wado.DownloadManager;
import org.weasis.dicom.explorer.wado.LoadRemoteDicomManifest;
import org.weasis.dicom.explorer.wado.LoadRemoteDicomURL;
import org.weasis.dicom.explorer.wado.LoadSeries;

public class DicomModel implements TreeModel, DataExplorerModel {
    private static final Logger LOGGER = LoggerFactory.getLogger(DicomModel.class);

    public static final String[] functions = { "get", "close" }; //$NON-NLS-1$ //$NON-NLS-2$
    public static final String NAME = "DICOM"; //$NON-NLS-1$
    public static final String PREFERENCE_NODE = "dicom.model"; //$NON-NLS-1$

    public static final TreeModelNode patient =
        new TreeModelNode(1, 0, TagW.PatientPseudoUID, new TagView(TagD.getTagFromIDs(Tag.PatientName, Tag.PatientID)));
    public static final TreeModelNode study = new TreeModelNode(2, 0, TagD.get(Tag.StudyInstanceUID),
        new TagView(TagD.getTagFromIDs(Tag.StudyDate, Tag.AccessionNumber, Tag.StudyID, Tag.StudyDescription)));
    public static final TreeModelNode series = new TreeModelNode(3, 0, TagW.SubseriesInstanceUID,
        new TagView(TagD.getTagFromIDs(Tag.SeriesDescription, Tag.SeriesNumber, Tag.SeriesTime)));

    public static final List<TreeModelNode> modelStrucure = new ArrayList<>(5);

    static {
        modelStrucure.add(TreeModelNode.ROOT);
        modelStrucure.add(patient);
        modelStrucure.add(study);
        modelStrucure.add(series);
    }

    public static final ExecutorService LOADING_EXECUTOR = ThreadUtil.buildNewSingleThreadExecutor("Dicom Model");

    private static final Collator collator = Collator.getInstance(Locale.getDefault());

    public static final Comparator<Object> PATIENT_COMPARATOR = new Comparator<Object>() {

        @Override
        public int compare(Object o1, Object o2) {
            return collator.compare(o1.toString(), o2.toString());
        }
    };

    public static final Comparator<Object> STUDY_COMPARATOR = new Comparator<Object>() {

        @Override
        public int compare(Object o1, Object o2) {
            if (o1 instanceof StudyPane && o2 instanceof StudyPane) {
                o1 = ((StudyPane) o1).dicomStudy;
                o2 = ((StudyPane) o2).dicomStudy;
            } else if (o1 instanceof DefaultMutableTreeNode && o2 instanceof DefaultMutableTreeNode) {
                o1 = ((DefaultMutableTreeNode) o1).getUserObject();
                o2 = ((DefaultMutableTreeNode) o2).getUserObject();
            }

            if (o1 instanceof MediaSeriesGroup && o2 instanceof MediaSeriesGroup) {
                MediaSeriesGroup st1 = (MediaSeriesGroup) o1;
                MediaSeriesGroup st2 = (MediaSeriesGroup) o2;
                LocalDateTime date1 = TagD.dateTime(Tag.StudyDate, Tag.StudyTime, st1);
                LocalDateTime date2 = TagD.dateTime(Tag.StudyDate, Tag.StudyTime, st2);
                // LOGGER.debug("date1: {} date2: {}", date1, date2);
                int c = -1;
                if (date1 != null && date2 != null) {
                    // Reverse chronological order.
                    c = date2.compareTo(date1);
                    if (c != 0) {
                        return c;
                    }
                }

                if (c == 0 || (date1 == null && date2 == null)) {
                    String d1 = TagD.getTagValue(st1, Tag.StudyDescription, String.class);
                    String d2 = TagD.getTagValue(st2, Tag.StudyDescription, String.class);
                    if (d1 != null && d2 != null) {
                        c = collator.compare(d1, d2);
                        if (c != 0) {
                            return c;
                        }
                    }
                    if (d1 == null && d2 != null) {
                        // Add o1 after o2
                        return 1;
                    }
                    // Add o2 after o1
                    return -1;
                } else {
                    if (date1 == null) {
                        // Add o1 after o2
                        return 1;
                    }
                    if (date2 == null) {
                        return -1;
                    }
                }
            } else {
                // Set non MediaSeriesGroup at the beginning of the list
                if (o1 instanceof MediaSeriesGroup) {
                    // Add o1 after o2
                    return 1;
                }
                if (o2 instanceof MediaSeriesGroup) {
                    return -1;
                }
            }
            return 0;
        }
    };

    public static final Comparator<Object> SERIES_COMPARATOR = new Comparator<Object>() {

        @Override
        public int compare(Object o1, Object o2) {

            if (o1 instanceof SeriesPane && o2 instanceof SeriesPane) {
                o1 = ((SeriesPane) o1).sequence;
                o2 = ((SeriesPane) o2).sequence;
            } else if (o1 instanceof DefaultMutableTreeNode && o2 instanceof DefaultMutableTreeNode) {
                o1 = ((DefaultMutableTreeNode) o1).getUserObject();
                o2 = ((DefaultMutableTreeNode) o2).getUserObject();
            }

            if (o1 instanceof MediaSeriesGroup && o2 instanceof MediaSeriesGroup) {
                MediaSeriesGroup st1 = (MediaSeriesGroup) o1;
                MediaSeriesGroup st2 = (MediaSeriesGroup) o2;

                Integer val1 = TagD.getTagValue(st1, Tag.SeriesNumber, Integer.class);
                Integer val2 = TagD.getTagValue(st2, Tag.SeriesNumber, Integer.class);
                int c = -1;
                if (val1 != null && val2 != null) {
                    c = val1.compareTo(val2);
                    if (c != 0) {
                        return c;
                    }
                }

                if (c == 0 || (val1 == null && val2 == null)) {
                    LocalDateTime date1 = TagD.dateTime(Tag.SeriesDate, Tag.SeriesTime, st1);
                    LocalDateTime date2 = TagD.dateTime(Tag.SeriesDate, Tag.SeriesTime, st2);
                    if (date1 != null && date2 != null) {
                        // Chronological order.
                        c = date1.compareTo(date2);
                        if (c != 0) {
                            return c;
                        }
                    }

                    if ((c == 0 || (date1 == null && date2 == null)) && st1 instanceof MediaSeries
                        && st2 instanceof MediaSeries) {
                        MediaElement<?> media1 = ((MediaSeries<? extends MediaElement<?>>) st1).getMedia(0, null, null);
                        MediaElement<?> media2 = ((MediaSeries<? extends MediaElement<?>>) st2).getMedia(0, null, null);
                        if (media1 != null && media2 != null) {
                            date1 = TagD.dateTime(Tag.AcquisitionDate, Tag.AcquisitionTime, media1);
                            date2 = TagD.dateTime(Tag.AcquisitionDate, Tag.AcquisitionTime, media2);
                            if (date1 == null) {
                                date1 = TagD.dateTime(Tag.ContentDate, Tag.ContentTime, media1);
                                if (date1 == null) {
                                    date1 =
                                        TagD.dateTime(Tag.DateOfSecondaryCapture, Tag.TimeOfSecondaryCapture, media1);
                                }
                            }
                            if (date2 == null) {
                                date2 = TagD.dateTime(Tag.ContentDate, Tag.ContentTime, media2);
                                if (date2 == null) {
                                    date2 =
                                        TagD.dateTime(Tag.DateOfSecondaryCapture, Tag.TimeOfSecondaryCapture, media2);
                                }
                            }
                            if (date1 != null && date2 != null) {
                                // Chronological order.
                                c = date1.compareTo(date2);
                                if (c != 0) {
                                    return c;
                                }
                            }
                            if (c == 0 || (date1 == null && date2 == null)) {
                                Double tag1 = TagD.getTagValue(media1, Tag.SliceLocation, Double.class);
                                Double tag2 = TagD.getTagValue(media2, Tag.SliceLocation, Double.class);
                                if (tag1 != null && tag2 != null) {
                                    c = tag1.compareTo(tag2);
                                    if (c != 0) {
                                        return c;
                                    }
                                }
                                if (c == 0 || (tag1 == null && tag2 == null)) {
                                    String nb1 = TagD.getTagValue(media1, Tag.StackID, String.class);
                                    String nb2 = TagD.getTagValue(media2, Tag.StackID, String.class);
                                    if (nb1 != null && nb2 != null) {
                                        c = nb1.compareTo(nb2);
                                        if (c != 0) {
                                            try {
                                                c = Integer.compare(Integer.parseInt(nb1), Integer.parseInt(nb2));
                                            } catch (Exception ex) {
                                            }
                                            return c;
                                        }
                                    }
                                    if (c == 0 || (nb1 == null && nb2 == null)) {
                                        return -1;
                                    }
                                    if (nb1 == null) {
                                        return 1;
                                    }
                                    return -1;
                                }
                                if (tag1 == null) {
                                    return 1;
                                }
                                return -1;
                            }
                            if (date1 == null) {
                                // Add o1 after o2
                                return 1;
                            }
                            // Add o2 after o1
                            return -1;
                        }
                        if (media2 == null) {
                            // Add o2 after o1
                            return -1;
                        }
                        return 1;
                    }
                    if (date1 == null) {
                        return 1;
                    }
                    return -1;
                }
                if (val1 == null) {
                    return 1;
                }
                return -1;
            }
            // Set non MediaSeriesGroup at the beginning of the list
            if (o1 instanceof MediaSeriesGroup) {
                // Add o1 after o2
                return 1;
            }
            if (o2 instanceof MediaSeriesGroup) {
                return -1;
            }
            return -1;
        }
    };

    private final Tree<MediaSeriesGroup> model;
    private PropertyChangeSupport propertyChange = null;
    private final SplittingRules splittingRules;

    public DicomModel() {
        model = new Tree<>(MediaSeriesGroupNode.rootNode);
        splittingRules = new SplittingRules();
    }

    @Override
    public synchronized List<Codec> getCodecPlugins() {
        ArrayList<Codec> codecPlugins = new ArrayList<>(1);
        synchronized (BundleTools.CODEC_PLUGINS) {
            for (Codec codec : BundleTools.CODEC_PLUGINS) {
                if (codec != null && "Sun java imageio".equals(codec.getCodecName()) == false //$NON-NLS-1$
                    && codec.isMimeTypeSupported("application/dicom") && !codecPlugins.contains(codec)) { //$NON-NLS-1$
                    codecPlugins.add(codec);
                }
            }
        }
        return codecPlugins;
    }

    @Override
    public Collection<MediaSeriesGroup> getChildren(MediaSeriesGroup node) {
        return model.getSuccessors(node);
    }

    @Override
    public MediaSeriesGroup getHierarchyNode(MediaSeriesGroup parent, Object value) {
        if (parent != null || value != null) {
            synchronized (model) {
                for (MediaSeriesGroup node : model.getSuccessors(parent)) {
                    if (node.equals(value)) {
                        return node;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public void addHierarchyNode(MediaSeriesGroup root, MediaSeriesGroup leaf) {
        synchronized (model) {
            model.addLeaf(root, leaf);
        }
    }

    @Override
    public void removeHierarchyNode(MediaSeriesGroup root, MediaSeriesGroup leaf) {
        synchronized (model) {
            Tree<MediaSeriesGroup> tree = model.getTree(root);
            if (tree != null) {
                tree.removeLeaf(leaf);
            }
        }
    }

    @Override
    public MediaSeriesGroup getParent(MediaSeriesGroup node, TreeModelNode modelNode) {
        if (null != node && modelNode != null) {
            if (node.getTagID().equals(modelNode.getTagElement())) {
                return node;
            }
            synchronized (model) {
                Tree<MediaSeriesGroup> tree = model.getTree(node);
                if (tree != null) {
                    Tree<MediaSeriesGroup> parent;
                    while ((parent = tree.getParent()) != null) {
                        if (parent.getHead().getTagID().equals(modelNode.getTagElement())) {
                            return parent.getHead();
                        }
                        tree = parent;
                    }
                }
            }
        }
        return null;
    }

    public void dispose() {
        synchronized (model) {
            for (Iterator<MediaSeriesGroup> iterator =
                this.getChildren(MediaSeriesGroupNode.rootNode).iterator(); iterator.hasNext();) {
                MediaSeriesGroup pt = iterator.next();
                Collection<MediaSeriesGroup> studies = this.getChildren(pt);
                for (Iterator<MediaSeriesGroup> iterator2 = studies.iterator(); iterator2.hasNext();) {
                    MediaSeriesGroup study = iterator2.next();
                    Collection<MediaSeriesGroup> seriesList = this.getChildren(study);
                    for (Iterator<MediaSeriesGroup> it = seriesList.iterator(); it.hasNext();) {
                        Object item = it.next();
                        if (item instanceof Series) {
                            ((Series) item).dispose();
                        }
                    }
                }
            }
        }
        model.clear();
    }

    @Override
    public String toString() {
        return NAME;
    }

    @Override
    public List<TreeModelNode> getModelStructure() {
        return modelStrucure;
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener propertychangelistener) {
        if (propertyChange == null) {
            propertyChange = new PropertyChangeSupport(this);
        }
        propertyChange.addPropertyChangeListener(propertychangelistener);
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener propertychangelistener) {
        if (propertyChange != null) {
            propertyChange.removePropertyChangeListener(propertychangelistener);
        }

    }

    @Override
    public void firePropertyChange(final ObservableEvent event) {
        if (propertyChange != null) {
            if (event == null) {
                throw new NullPointerException();
            }
            if (SwingUtilities.isEventDispatchThread()) {
                propertyChange.firePropertyChange(event);
            } else {
                SwingUtilities.invokeLater(new Runnable() {

                    @Override
                    public void run() {
                        propertyChange.firePropertyChange(event);
                    }
                });
            }
        }
    }

    public void mergeSeries(List<MediaSeries<? extends MediaElement<?>>> seriesList) {
        if (seriesList != null && seriesList.size() > 1) {
            String uid = TagD.getTagValue(seriesList.get(0), Tag.SeriesInstanceUID, String.class);
            boolean sameOrigin = true;
            if (uid != null) {
                for (int i = 1; i < seriesList.size(); i++) {
                    if (!uid.equals(TagD.getTagValue(seriesList.get(i), Tag.SeriesInstanceUID))) {
                        sameOrigin = false;
                        break;
                    }
                }
            }
            if (sameOrigin) {
                int min = Integer.MAX_VALUE;
                MediaSeries<? extends MediaElement<?>> base = seriesList.get(0);
                for (MediaSeries<? extends MediaElement<?>> series : seriesList) {
                    Integer splitNb = (Integer) series.getTagValue(TagW.SplitSeriesNumber);
                    if (splitNb != null && min > splitNb) {
                        min = splitNb;
                        base = series;
                    }
                }
                for (MediaSeries<? extends MediaElement<?>> series : seriesList) {
                    if (series != base) {
                        base.addAll((Collection) series.getMedias(null, null));
                        removeSeries(series);
                    }
                }
                // Force to sort the new merged media list
                List sortedMedias = base.getSortedMedias(null);
                Collections.sort(sortedMedias, SortSeriesStack.instanceNumber);
                // update observer
                this.firePropertyChange(
                    new ObservableEvent(ObservableEvent.BasicAction.Replace, DicomModel.this, base, base));
            }
        }
    }

    public void removeSpecialElement(DicomSpecialElement dicomSpecialElement) {
        if (dicomSpecialElement == null) {
            return;
        }

        String patientPseudoUID = (String) dicomSpecialElement.getTagValue(TagW.PatientPseudoUID);
        MediaSeriesGroup patientGroup = getHierarchyNode(MediaSeriesGroupNode.rootNode, patientPseudoUID);

        if (patientGroup == null) {
            return;
        }

        String studyUID = TagD.getTagValue(dicomSpecialElement, Tag.StudyInstanceUID, String.class);
        MediaSeriesGroup studyGroup = getHierarchyNode(patientGroup, studyUID);
        if (studyGroup == null) {
            return;
        }

        String seriesUID = TagD.getTagValue(dicomSpecialElement, Tag.SeriesInstanceUID, String.class);
        Series<?> dicomSeries = (Series<?>) getHierarchyNode(studyGroup, seriesUID);
        if (dicomSeries == null) {
            return;
        }

        if (isSpecialModality(dicomSeries)) {

            List<DicomSpecialElement> specialElementList =
                (List<DicomSpecialElement>) dicomSeries.getTagValue(TagW.DicomSpecialElementList);

            List<DicomSpecialElement> patientSpecialElementList =
                (List<DicomSpecialElement>) patientGroup.getTagValue(TagW.DicomSpecialElementList);

            if (specialElementList == null || patientSpecialElementList == null) {
                return;
            }

            specialElementList.remove(dicomSpecialElement);

            if (patientSpecialElementList.remove(dicomSpecialElement)) {
                firePropertyChange(
                    new ObservableEvent(ObservableEvent.BasicAction.Update, this, null, dicomSpecialElement));
            }

            if (specialElementList.isEmpty()) {
                removeSeries(dicomSeries);
            }
        }
    }

    public void removeSeries(MediaSeriesGroup dicomSeries) {
        if (dicomSeries != null) {
            if (!DownloadManager.TASKS.isEmpty() && dicomSeries instanceof DicomSeries) {
                DownloadManager.stopDownloading((DicomSeries) dicomSeries, this);
            }
            // remove first series in UI (Dicom Explorer, Viewer using this series)
            firePropertyChange(
                new ObservableEvent(ObservableEvent.BasicAction.Remove, DicomModel.this, null, dicomSeries));
            // remove in the data model
            MediaSeriesGroup studyGroup = getParent(dicomSeries, DicomModel.study);
            removeHierarchyNode(studyGroup, dicomSeries);
            LOGGER.info("Remove Series: {}", dicomSeries); //$NON-NLS-1$
            dicomSeries.dispose();
        }
    }

    public void removeStudy(MediaSeriesGroup studyGroup) {
        if (studyGroup != null) {
            if (!DownloadManager.TASKS.isEmpty()) {
                Collection<MediaSeriesGroup> seriesList = getChildren(studyGroup);
                for (Iterator<MediaSeriesGroup> it = seriesList.iterator(); it.hasNext();) {
                    MediaSeriesGroup group = it.next();
                    if (group instanceof DicomSeries) {
                        DownloadManager.stopDownloading((DicomSeries) group, this);
                    }
                }
            }
            firePropertyChange(
                new ObservableEvent(ObservableEvent.BasicAction.Remove, DicomModel.this, null, studyGroup));
            Collection<MediaSeriesGroup> seriesList = getChildren(studyGroup);
            for (Iterator<MediaSeriesGroup> it = seriesList.iterator(); it.hasNext();) {
                MediaSeriesGroup group = it.next();
                group.dispose();
            }
            MediaSeriesGroup patientGroup = getParent(studyGroup, DicomModel.patient);
            removeHierarchyNode(patientGroup, studyGroup);
            LOGGER.info("Remove Study: {}", studyGroup); //$NON-NLS-1$
        }
    }

    public void removePatient(MediaSeriesGroup patientGroup) {
        if (patientGroup != null) {
            if (!DownloadManager.TASKS.isEmpty()) {
                Collection<MediaSeriesGroup> studyList = getChildren(patientGroup);
                for (Iterator<MediaSeriesGroup> it = studyList.iterator(); it.hasNext();) {
                    MediaSeriesGroup studyGroup = it.next();
                    Collection<MediaSeriesGroup> seriesList = getChildren(studyGroup);
                    for (Iterator<MediaSeriesGroup> it2 = seriesList.iterator(); it2.hasNext();) {
                        MediaSeriesGroup group = it2.next();
                        if (group instanceof DicomSeries) {
                            DownloadManager.stopDownloading((DicomSeries) group, this);
                        }
                    }
                }
            }
            firePropertyChange(
                new ObservableEvent(ObservableEvent.BasicAction.Remove, DicomModel.this, null, patientGroup));
            Collection<MediaSeriesGroup> studyList = getChildren(patientGroup);
            for (Iterator<MediaSeriesGroup> it = studyList.iterator(); it.hasNext();) {
                MediaSeriesGroup studyGroup = it.next();
                Collection<MediaSeriesGroup> seriesList = getChildren(studyGroup);
                for (Iterator<MediaSeriesGroup> it2 = seriesList.iterator(); it2.hasNext();) {
                    MediaSeriesGroup group = it2.next();
                    if (group instanceof DicomSeries) {
                        ((DicomSeries) group).dispose();
                    }
                }
            }
            removeHierarchyNode(MediaSeriesGroupNode.rootNode, patientGroup);
            LOGGER.info("Remove Patient: {}", patientGroup); //$NON-NLS-1$
        }
    }

    /**
     * DicomSpecialElement are added at patientGroupLevel since StudyInstanceUID and SeriesInstanceUID are not relevant
     * with the CurrentRequestedProcedureEvidenceSequence which can reference any SOPInstance of any Study and Series of
     * the Patient
     *
     * @param series
     */
    public void addSpecialModality(Series series) {

        List<DicomSpecialElement> seriesSpecialElementList =
            (List<DicomSpecialElement>) series.getTagValue(TagW.DicomSpecialElementList);
        if (seriesSpecialElementList == null || seriesSpecialElementList.isEmpty()) {
            return;
        }

        MediaSeriesGroup patientGroup = getParent(series, DicomModel.patient);

        if (patientGroup == null) {
            return;
        }

        List<DicomSpecialElement> patientSpecialElementList =
            (List<DicomSpecialElement>) patientGroup.getTagValue(TagW.DicomSpecialElementList);

        if (patientSpecialElementList == null) {
            patientSpecialElementList = new CopyOnWriteArrayList<>();
            patientGroup.setTag(TagW.DicomSpecialElementList, patientSpecialElementList);
        }
        for (DicomSpecialElement seriesSpecialElement : seriesSpecialElementList) {
            if (!patientSpecialElementList.contains(seriesSpecialElement)) {
                patientSpecialElementList.add(seriesSpecialElement);
            }
        }

    }

    public static boolean isSpecialModality(Series series) {
        String modality = (series == null) ? null : TagD.getTagValue(series, Tag.Modality, String.class);
        return modality != null && ("PR".equals(modality) || "KO".equals(modality)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public static Collection<KOSpecialElement> getKoSpecialElements(MediaSeries<DicomImageElement> dicomSeries) {
        // Get all DicomSpecialElement at patient level
        List<DicomSpecialElement> specialElementList = getSpecialElements(dicomSeries);

        if (specialElementList != null) {
            String referencedSeriesInstanceUID = TagD.getTagValue(dicomSeries, Tag.SeriesInstanceUID, String.class);
            return DicomSpecialElement.getKoSpecialElements(specialElementList, referencedSeriesInstanceUID);
        }
        return Collections.emptyList();
    }

    public static Collection<RejectedKOSpecialElement> getRejectionKoSpecialElements(
        MediaSeries<DicomImageElement> dicomSeries) {
        // Get all DicomSpecialElement at patient level
        List<DicomSpecialElement> specialElementList = getSpecialElements(dicomSeries);

        if (specialElementList != null) {
            String referencedSeriesInstanceUID = TagD.getTagValue(dicomSeries, Tag.SeriesInstanceUID, String.class);
            return DicomSpecialElement.getRejectionKoSpecialElements(specialElementList, referencedSeriesInstanceUID);
        }
        return Collections.emptyList();
    }

    public static RejectedKOSpecialElement getRejectionKoSpecialElement(MediaSeries<DicomImageElement> dicomSeries,
        String sopUID, Integer frameNumber) {
        // Get all DicomSpecialElement at patient level
        List<DicomSpecialElement> specialElementList = getSpecialElements(dicomSeries);

        if (specialElementList != null) {
            String referencedSeriesInstanceUID = TagD.getTagValue(dicomSeries, Tag.SeriesInstanceUID, String.class);
            return DicomSpecialElement.getRejectionKoSpecialElement(specialElementList, referencedSeriesInstanceUID,
                sopUID, frameNumber);
        }
        return null;
    }

    public static List<PRSpecialElement> getPrSpecialElements(MediaSeries<DicomImageElement> dicomSeries, String sopUID,
        Integer frameNumber) {
        // Get all DicomSpecialElement at patient level
        List<DicomSpecialElement> specialElementList = getSpecialElements(dicomSeries);

        if (!specialElementList.isEmpty()) {
            String referencedSeriesInstanceUID = TagD.getTagValue(dicomSeries, Tag.SeriesInstanceUID, String.class);
            return DicomSpecialElement.getPRSpecialElements(specialElementList, referencedSeriesInstanceUID, sopUID,
                frameNumber);
        }
        return Collections.emptyList();
    }

    public static List<DicomSpecialElement> getSpecialElements(MediaSeries<DicomImageElement> dicomSeries) {
        if (dicomSeries == null) {
            return Collections.emptyList();
        }

        List<DicomSpecialElement> list = null;
        DataExplorerModel model = (DataExplorerModel) dicomSeries.getTagValue(TagW.ExplorerModel);

        if (model instanceof DicomModel) {
            MediaSeriesGroup patientGroup = ((DicomModel) model).getParent(dicomSeries, DicomModel.patient);

            if (patientGroup != null) {
                list = (List<DicomSpecialElement>) patientGroup.getTagValue(TagW.DicomSpecialElementList);
            }
        }
        return list == null ? Collections.emptyList() : list;
    }

    public static <E> List<E> getSpecialElements(MediaSeriesGroup group, Class<E> clazz) {
        if (group != null && clazz != null && clazz.isAssignableFrom(clazz)) {
            List<DicomSpecialElement> kos = (List<DicomSpecialElement>) group.getTagValue(TagW.DicomSpecialElementList);
            if (kos != null) {
                List<E> list = new ArrayList<>();
                for (DicomSpecialElement el : kos) {
                    if (clazz.isInstance(el)) {
                        list.add((E) el);
                    }
                }
                return list;
            }
        }
        return Collections.emptyList();
    }

    public static <E> E getFirstSpecialElement(MediaSeriesGroup group, Class<E> clazz) {
        if (group != null && clazz != null && clazz.isAssignableFrom(clazz)) {
            List<DicomSpecialElement> kos = (List<DicomSpecialElement>) group.getTagValue(TagW.DicomSpecialElementList);
            if (kos != null) {
                for (DicomSpecialElement el : kos) {
                    if (clazz.isInstance(el)) {
                        return (E) el;
                    }
                }
            }
        }
        return null;
    }

    public static boolean hasSpecialElements(MediaSeriesGroup group, Class<? extends DicomSpecialElement> clazz) {
        if (group != null && clazz != null) {
            List<DicomSpecialElement> kos = (List<DicomSpecialElement>) group.getTagValue(TagW.DicomSpecialElementList);
            if (kos != null) {
                for (DicomSpecialElement el : kos) {
                    if (clazz.isInstance(el)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public void openrelatedSeries(KOSpecialElement koSpecialElement, MediaSeriesGroup patient) {
        if (koSpecialElement != null && patient != null) {
            SeriesViewerFactory plugin = UIManager.getViewerFactory(DicomMediaIO.SERIES_MIMETYPE);
            if (plugin != null && !(plugin instanceof MimeSystemAppFactory)) {
                Set<String> koSet = koSpecialElement.getReferencedSeriesInstanceUIDSet();
                List<MediaSeries<? extends MediaElement<?>>> seriesList = new ArrayList<>();

                for (MediaSeriesGroup st : this.getChildren(patient)) {
                    for (MediaSeriesGroup s : this.getChildren(st)) {
                        if (koSet.contains(TagD.getTagValue(s, Tag.SeriesInstanceUID))) {
                            seriesList.add((MediaSeries<? extends MediaElement<?>>) s);
                        }
                    }
                }
                if (seriesList.size() > 0) {
                    String uid = UUID.randomUUID().toString();
                    Map<String, Object> props = Collections.synchronizedMap(new HashMap<String, Object>());
                    props.put(ViewerPluginBuilder.CMP_ENTRY_BUILD_NEW_VIEWER, false);
                    props.put(ViewerPluginBuilder.BEST_DEF_LAYOUT, false);
                    props.put(ViewerPluginBuilder.ICON,
                        new ImageIcon(getClass().getResource("/icon/16x16/key-images.png"))); //$NON-NLS-1$
                    props.put(ViewerPluginBuilder.UID, uid);
                    ViewerPluginBuilder builder = new ViewerPluginBuilder(plugin, seriesList, this, props);
                    ViewerPluginBuilder.openSequenceInPlugin(builder);
                    this.firePropertyChange(
                        new ObservableEvent(ObservableEvent.BasicAction.Select, uid, null, koSpecialElement));
                }
            }
        }
    }

    private void splitSeries(DicomMediaIO dicomReader, Series original, MediaElement media) {
        Series s = splitSeries(dicomReader, original);
        s.addMedia(media);
        LOGGER.info("Series splitting: {}", s); //$NON-NLS-1$
    }

    private Series splitSeries(DicomMediaIO dicomReader, Series original) {
        MediaSeriesGroup study = getParent(original, DicomModel.study);
        String seriesUID = TagD.getTagValue(original, Tag.SeriesInstanceUID, String.class);
        int k = 1;
        while (true) {
            String uid = "#" + k + "." + seriesUID; //$NON-NLS-1$ //$NON-NLS-2$
            MediaSeriesGroup group = getHierarchyNode(study, uid);
            if (group == null) {
                break;
            }
            k++;
        }
        String uid = "#" + k + "." + seriesUID; //$NON-NLS-1$ //$NON-NLS-2$
        Series s = dicomReader.buildSeries(uid);
        dicomReader.writeMetaData(s);
        Object val = original.getTagValue(TagW.SplitSeriesNumber);
        if (val == null) {
            original.setTag(TagW.SplitSeriesNumber, 1);
        }
        s.setTag(TagW.SplitSeriesNumber, k + 1);
        s.setTag(TagW.ExplorerModel, this);
        addHierarchyNode(study, s);
        LOGGER.info("Series splitting: {}", s); //$NON-NLS-1$
        return s;
    }

    private void replaceSeries(DicomMediaIO dicomReader, Series original, MediaElement media) {
        MediaSeriesGroup study = getParent(original, DicomModel.study);
        String seriesUID = TagD.getTagValue(original, Tag.SeriesInstanceUID, String.class);

        int k = 1;
        while (true) {
            String uid = "#" + k + "." + seriesUID; //$NON-NLS-1$ //$NON-NLS-2$
            MediaSeriesGroup group = getHierarchyNode(study, uid);
            if (group == null) {
                break;
            }
            k++;
        }
        String uid = "#" + k + "." + seriesUID; //$NON-NLS-1$ //$NON-NLS-2$
        Series s = dicomReader.buildSeries(uid);
        dicomReader.writeMetaData(s);
        Object val = original.getTagValue(TagW.SplitSeriesNumber);
        if (val == null) {
            // -1 convention to exclude this Series
            original.setTag(TagW.SplitSeriesNumber, -1);
        }
        s.setTag(TagW.SplitSeriesNumber, k);
        s.setTag(TagW.ExplorerModel, this);
        addHierarchyNode(study, s);
        s.addMedia(media);
        LOGGER.info("Replace Series: {}", s); //$NON-NLS-1$
    }

    private void rebuildSeries(DicomMediaIO dicomReader, MediaElement media) {
        String patientPseudoUID = (String) dicomReader.getTagValue(TagW.PatientPseudoUID);
        MediaSeriesGroup pt = getHierarchyNode(MediaSeriesGroupNode.rootNode, patientPseudoUID);
        if (pt == null) {
            pt = new MediaSeriesGroupNode(TagW.PatientPseudoUID, patientPseudoUID, DicomModel.patient.getTagView());
            dicomReader.writeMetaData(pt);
            addHierarchyNode(MediaSeriesGroupNode.rootNode, pt);
            LOGGER.info("Adding patient: {}", pt); //$NON-NLS-1$
        }

        String studyUID = TagD.getTagValue(dicomReader, Tag.StudyInstanceUID, String.class);
        MediaSeriesGroup st = getHierarchyNode(pt, studyUID);
        if (st == null) {
            st = new MediaSeriesGroupNode(TagD.get(Tag.StudyInstanceUID), studyUID, DicomModel.study.getTagView());
            dicomReader.writeMetaData(st);
            addHierarchyNode(pt, st);
        }
        String seriesUID = TagD.getTagValue(dicomReader, Tag.SeriesInstanceUID, String.class);
        Series dicomSeries = (Series) getHierarchyNode(st, seriesUID);

        if (dicomSeries == null) {
            dicomSeries = dicomReader.buildSeries(seriesUID);
            dicomReader.writeMetaData(dicomSeries);
            dicomSeries.setTag(TagW.ExplorerModel, this);
            addHierarchyNode(st, dicomSeries);
            LOGGER.info("Series rebuilding: {}", dicomSeries); //$NON-NLS-1$
        }
        dicomSeries.addMedia(media);

        // Load image and create thumbnail in this Thread
        Thumbnail t = (Thumbnail) dicomSeries.getTagValue(TagW.Thumbnail);
        if (t == null) {
            t = DicomExplorer.createThumbnail(dicomSeries, this, Thumbnail.DEFAULT_SIZE);
            dicomSeries.setTag(TagW.Thumbnail, t);
            t.repaint();
        }
        firePropertyChange(new ObservableEvent(ObservableEvent.BasicAction.Add, this, null, dicomSeries));
    }

    @Override
    public boolean applySplittingRules(Series original, MediaElement media) {
        if (media != null && media.getMediaReader() instanceof DicomMediaIO) {
            DicomMediaIO dicomReader = (DicomMediaIO) media.getMediaReader();
            String seriesUID = TagD.getTagValue(original, Tag.SeriesInstanceUID, String.class);
            if (!seriesUID.equals(TagD.getTagValue(dicomReader, Tag.SeriesInstanceUID))) {
                rebuildSeries(dicomReader, media);
                return true;
            }
            if (original instanceof DicomSeries) {
                DicomSeries initialSeries = (DicomSeries) original;
                // Handle cases when the Series is created before getting the image (downloading)
                if (media instanceof DicomVideoElement || media instanceof DicomEncapDocElement) {
                    if (original.size(null) > 0) {
                        // When the series already contains elements (images), always split video and document
                        splitSeries(dicomReader, original, media);
                    } else {
                        replaceSeries(dicomReader, original, media);
                    }
                    return true;
                }
                if (media instanceof DicomSpecialElement) {
                    List<DicomSpecialElement> specialElementList =
                        (List<DicomSpecialElement>) initialSeries.getTagValue(TagW.DicomSpecialElementList);

                    if (specialElementList == null) {
                        specialElementList = new CopyOnWriteArrayList<>();
                        initialSeries.setTag(TagW.DicomSpecialElementList, specialElementList);
                    } else if ("SR".equals(TagD.getTagValue(dicomReader, Tag.Modality))) { //$NON-NLS-1$
                        // Split SR series to have only one object by series
                        Series s = splitSeries(dicomReader, initialSeries);
                        specialElementList = new CopyOnWriteArrayList<>();
                        specialElementList.add((DicomSpecialElement) media);
                        s.setTag(TagW.DicomSpecialElementList, specialElementList);
                        return false;
                    }
                    specialElementList.add((DicomSpecialElement) media);
                    return false;
                }

                int frames = dicomReader.getMediaElementNumber();
                if (frames < 1) {
                    initialSeries.addMedia(media);
                } else {
                    Modality modality =
                        Modality.getModality(TagD.getTagValue(initialSeries, Tag.Modality, String.class));

                    SplittingModalityRules splitRules =
                        splittingRules.getSplittingModalityRules(modality, Modality.Default);
                    List<Rule> rules;
                    if (splitRules == null) {
                        rules = Collections.emptyList();
                    } else {
                        rules = frames > 1 ? splitRules.getMultiFrameRules() : splitRules.getSingleFrameRules();
                    }
                    // If similar add to the original series
                    if (isSimilar(rules, initialSeries, media)) {
                        initialSeries.addMedia(media);
                        return false;
                    }

                    // else try to find a similar previous split series
                    MediaSeriesGroup study = getParent(initialSeries, DicomModel.study);
                    int k = 1;
                    while (true) {
                        String uid = "#" + k + "." + seriesUID; //$NON-NLS-1$ //$NON-NLS-2$
                        MediaSeriesGroup group = getHierarchyNode(study, uid);
                        if (group instanceof DicomSeries) {
                            if (isSimilar(rules, (DicomSeries) group, media)) {
                                ((DicomSeries) group).addMedia(media);
                                return false;
                            }
                        } else {
                            break;
                        }
                        k++;
                    }
                    // no matching series exists, so split series
                    splitSeries(dicomReader, initialSeries, media);
                    return true;
                }
            } else if (original instanceof DicomVideoSeries || original instanceof DicomEncapDocSeries) {
                if (original.size(null) > 0) {
                    // Always split when it is a video or a encapsulated document
                    if (media instanceof DicomVideoElement || media instanceof DicomEncapDocElement) {
                        splitSeries(dicomReader, original, media);
                        return true;
                    } else {
                        findMatchingSeriesOrsplit(original, media);
                    }
                } else {
                    original.addMedia(media);
                }
            }
        }
        return false;

    }

    private boolean findMatchingSeriesOrsplit(Series original, MediaElement media) {
        DicomMediaIO dicomReader = (DicomMediaIO) media.getMediaReader();
        int frames = dicomReader.getMediaElementNumber();
        if (frames < 1) {
            original.addMedia(media);
        } else {
            String seriesUID = TagD.getTagValue(original, Tag.SeriesInstanceUID, String.class);

            Modality modality = Modality.getModality(TagD.getTagValue(original, Tag.Modality, String.class));
            SplittingModalityRules splitRules = splittingRules.getSplittingModalityRules(modality, Modality.Default);
            List<Rule> rules;
            if (splitRules == null) {
                rules = Collections.emptyList();
            } else {
                rules = frames > 1 ? splitRules.getMultiFrameRules() : splitRules.getSingleFrameRules();
            }
            // If similar add to the original series
            if (isSimilar(rules, original, media)) {
                original.addMedia(media);
                return false;
            }

            // else try to find a similar previous split series
            MediaSeriesGroup study = getParent(original, DicomModel.study);
            int k = 1;
            while (true) {
                String uid = "#" + k + "." + seriesUID; //$NON-NLS-1$ //$NON-NLS-2$
                MediaSeriesGroup group = getHierarchyNode(study, uid);
                if (group instanceof Series) {
                    if (isSimilar(rules, (Series) group, media)) {
                        ((Series) group).addMedia(media);
                        return false;
                    }
                } else {
                    break;
                }
                k++;
            }
            // no matching series exists, so split series
            splitSeries(dicomReader, original, media);
            return true;
        }
        return false;
    }

    private static boolean isSimilar(List<Rule> list, Series<?> s, final MediaElement<?> media) {
        final MediaElement<?> firstMedia = s.getMedia(0, null, null);
        if (firstMedia == null) {
            // no image
            return true;
        }
        // Not similar when the instances have different classes (even when inheriting class)
        if (firstMedia.getClass() != media.getClass()) {
            return false;
        }

        for (Rule rule : list) {
            if (!rule.isTagValueMatching(firstMedia, media)) {
                return false;
            }
        }
        return true;
    }

    public void get(String[] argv) throws IOException {
        final String[] usage = { "Load DICOM files remotely or locally", "Usage: dicom:get [Options] SOURCE", //$NON-NLS-1$ //$NON-NLS-2$
            "  -l --local		Open DICOMs from local disk", //$NON-NLS-1$
            "  -r --remote       Open DICOMs from an URL", //$NON-NLS-1$
            "  -p --portable       Open DICOMs from default directories at the same level of the executable", //$NON-NLS-1$
            "  -i --iwado        Open DICOMs from an XML (GZIP, Base64) file containing UIDs", //$NON-NLS-1$
            "  -w --wado		Open DICOMs from an XML (URL) file containing UIDs", "  -? --help		show help" }; //$NON-NLS-1$ //$NON-NLS-2$

        final Option opt = Options.compile(usage).parse(argv);
        final List<String> args = opt.args();

        if (opt.isSet("help") || (args.isEmpty() && !opt.isSet("portable"))) { //$NON-NLS-1$ //$NON-NLS-2$
            opt.usage();
            return;
        }

        GuiExecutor.instance().execute(() -> {
            firePropertyChange(
                new ObservableEvent(ObservableEvent.BasicAction.Select, DicomModel.this, null, DicomModel.this));
            getCommand(opt, args);
        });
    }

    private void getCommand(Option opt, List<String> args) {
        // start importing local dicom series list
        if (opt.isSet("local")) { //$NON-NLS-1$
            File[] files = new File[args.size()];
            for (int i = 0; i < files.length; i++) {
                files[i] = new File(args.get(i));
            }
            LOADING_EXECUTOR.execute(new LoadLocalDicom(files, true, DicomModel.this));
        } else if (opt.isSet("remote")) { //$NON-NLS-1$
            LOADING_EXECUTOR.execute(new LoadRemoteDicomURL(args.toArray(new String[args.size()]), DicomModel.this));
        }
        // build WADO series list to download
        else if (opt.isSet("wado")) { //$NON-NLS-1$
            LOADING_EXECUTOR
                .execute(new LoadRemoteDicomManifest(args.toArray(new String[args.size()]), DicomModel.this));
        } else if (opt.isSet("iwado")) { //$NON-NLS-1$
            File[] xmlFiles = new File[args.size()];
            for (int i = 0; i < xmlFiles.length; i++) {
                try {
                    File tempFile = File.createTempFile("wado_", ".xml", AppProperties.APP_TEMP_DIR); //$NON-NLS-1$ //$NON-NLS-2$
                    if (GzipManager.gzipUncompressToFile(Base64.getDecoder().decode(args.get(i)), tempFile)) {
                        xmlFiles[i] = tempFile;
                    }

                } catch (Exception e) {
                    LOGGER.info("ungzip manifest", e); //$NON-NLS-1$
                }
            }
            LOADING_EXECUTOR.execute(new LoadRemoteDicomManifest(xmlFiles, DicomModel.this));
        }
        // Get DICOM folder (by default DICOM, dicom, IHE_PDI, ihe_pdi) at the same level at the Weasis
        // executable file
        else if (opt.isSet("portable")) { //$NON-NLS-1$

            String prop = System.getProperty("weasis.portable.dicom.directory"); //$NON-NLS-1$
            String baseDir = System.getProperty("weasis.portable.dir"); //$NON-NLS-1$

            if (prop != null && baseDir != null) {
                String[] dirs = prop.split(","); //$NON-NLS-1$
                for (int i = 0; i < dirs.length; i++) {
                    dirs[i] = dirs[i].trim().replaceAll("/", File.separator); //$NON-NLS-1$
                }
                File[] files = new File[dirs.length];
                boolean notCaseSensitive = AppProperties.OPERATING_SYSTEM.startsWith("win");//$NON-NLS-1$
                if (notCaseSensitive) {
                    Arrays.sort(dirs, String.CASE_INSENSITIVE_ORDER);
                }
                String last = null;
                for (int i = 0; i < files.length; i++) {
                    if (notCaseSensitive && last != null && dirs[i].equalsIgnoreCase(last)) {
                        last = null;
                    } else {
                        last = dirs[i];
                        files[i] = new File(baseDir, dirs[i]);
                    }
                }

                List<LoadSeries> loadSeries = null;
                File dcmDirFile = new File(baseDir, "DICOMDIR"); //$NON-NLS-1$
                if (dcmDirFile.canRead()) {
                    // Copy images in cache if property weasis.portable.dicom.cache = true (default is true)
                    DicomDirLoader dirImport = new DicomDirLoader(dcmDirFile, DicomModel.this,
                        DicomManager.getInstance().isPortableDirCache());
                    loadSeries = dirImport.readDicomDir();
                }
                if (loadSeries != null && !loadSeries.isEmpty()) {
                    LOADING_EXECUTOR.execute(new LoadDicomDir(loadSeries, DicomModel.this));
                } else {
                    LOADING_EXECUTOR.execute(new LoadLocalDicom(files, true, DicomModel.this));
                }
            }
        }
    }

    public void close(String[] argv) throws IOException {
        final String[] usage = { "Remove DICOM files in Dicom Explorer", //$NON-NLS-1$
            "Usage: dicom:close [patient | study | series] [ARGS]", //$NON-NLS-1$
            "  -a --all Close all patients", //$NON-NLS-1$
            "  -p --patient <args>	Close patient, [arg] is patientUID (PatientID + Patient Birth Date, by default)", //$NON-NLS-1$
            "  -y --study <args>	Close study, [arg] is Study Instance UID", //$NON-NLS-1$
            "  -s --series <args>	Close series, [arg] is Series Instance UID", "  -? --help		show help" }; //$NON-NLS-1$ //$NON-NLS-2$
        final Option opt = Options.compile(usage).parse(argv);
        final List<String> args = opt.args();

        if (opt.isSet("help") || (args.isEmpty() && !opt.isSet("all"))) { //$NON-NLS-1$ //$NON-NLS-2$
            opt.usage();
            return;
        }

        GuiExecutor.instance().execute(() -> {
            firePropertyChange(
                new ObservableEvent(ObservableEvent.BasicAction.Select, DicomModel.this, null, DicomModel.this));
            closeCommand(opt, args);

        });
    }

    private void closeCommand(Option opt, List<String> args) {
        // start build local dicom series list
        if (opt.isSet("patient")) { //$NON-NLS-1$
            for (String patientUID : args) {
                MediaSeriesGroup patientGroup;
                // In Weasis, Global Identity of the patient is composed of the patientID and the name by default
                // TODO handle preferences choice for patientUID, see DicomMediaUtils.buildPatientPseudoUID()
                patientGroup = getHierarchyNode(MediaSeriesGroupNode.rootNode, patientUID);
                if (patientGroup == null) {
                    System.out.println("Cannot find patient: " + patientUID); //$NON-NLS-1$
                    continue;
                } else {
                    removePatient(patientGroup);
                }
            }
        } else if (opt.isSet("all")) { //$NON-NLS-1$
            for (MediaSeriesGroup patientGroup : model.getSuccessors(MediaSeriesGroupNode.rootNode)) {
                removePatient(patientGroup);
            }
        } else if (opt.isSet("study")) { //$NON-NLS-1$
            for (String studyUID : args) {
                for (MediaSeriesGroup ptGroup : model.getSuccessors(MediaSeriesGroupNode.rootNode)) {
                    MediaSeriesGroup stGroup = getHierarchyNode(ptGroup, studyUID);
                    if (stGroup != null) {
                        removeStudy(stGroup);
                        break;
                    }
                }
            }
        } else if (opt.isSet("series")) { //$NON-NLS-1$
            for (String seriesUID : args) {
                patientLevel: for (MediaSeriesGroup ptGroup : model.getSuccessors(MediaSeriesGroupNode.rootNode)) {
                    for (MediaSeriesGroup stGroup : model.getSuccessors(ptGroup)) {
                        MediaSeriesGroup series = getHierarchyNode(stGroup, seriesUID);
                        if (series instanceof Series) {
                            removeSeries(series);
                            break patientLevel;
                        }
                    }
                }
            }
        }
    }

    @Override
    public TreeModelNode getTreeModelNodeForNewPlugin() {
        return patient;
    }

}
