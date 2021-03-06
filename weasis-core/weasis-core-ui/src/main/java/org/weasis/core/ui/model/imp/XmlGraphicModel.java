package org.weasis.core.ui.model.imp;

import java.util.Arrays;
import java.util.List;

import javax.xml.bind.annotation.XmlRootElement;

import org.weasis.core.api.media.data.ImageElement;
import org.weasis.core.api.media.data.TagW;
import org.weasis.core.ui.model.AbstractGraphicModel;
import org.weasis.core.ui.model.ReferencedImage;
import org.weasis.core.ui.model.ReferencedSeries;

@XmlRootElement(name = "presentation")
public class XmlGraphicModel extends AbstractGraphicModel {
    private static final long serialVersionUID = 2427740058858913568L;

    public XmlGraphicModel() {
        super();
    }

    public XmlGraphicModel(ImageElement img) {
        super(buildReferences(img));
    }
    
    private static List<ReferencedSeries> buildReferences(ImageElement img) {
        String UUID = (String) img.getTagValue(TagW.get("SOPInstanceUID"));
        if (UUID == null) {
            UUID =  java.util.UUID.randomUUID().toString();
            img.setTag(TagW.get("SOPInstanceUID"), UUID);
        }
        
        String seriesUUID = (String) img.getTagValue(TagW.get("SeriesInstanceUID"));
        if (seriesUUID == null) {
            seriesUUID = java.util.UUID.randomUUID().toString();
            img.setTag(TagW.get("SeriesInstanceUID"), seriesUUID);
        }
        
        List<ReferencedImage> images = Arrays.asList(new ReferencedImage(UUID));
        return Arrays.asList(new ReferencedSeries(seriesUUID, images));
    }
}