package org.weasis.core.ui.model.graphic.imp;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.Arrays;
import java.util.List;

import org.weasis.core.api.service.WProperties;
import org.weasis.core.ui.test.testers.GraphicTester;

public class PixelInfoGraphicTest extends GraphicTester<PixelInfoGraphic> {
    private static final String XML_0 = "/graphic/pixel/pixel.graphic.0.xml";
    private static final String XML_1 = "/graphic/pixel/pixel.graphic.1.xml";
    
    static final String BASIC_TPL = 
        "<pixelInfo fill=\"%s\" showLabel=\"%s\" thickness=\"%s\" uuid=\"%s\">"
     +      "<paint rgb=\"%s\"/>"
     +      "<pts/>"
     +  "</pixelInfo>";

    public static final PixelInfoGraphic COMPLETE_OBJECT =  new PixelInfoGraphic();
    static {
        COMPLETE_OBJECT.setUuid(GRAPHIC_UUID_1);
        COMPLETE_OBJECT.setLineThickness(2.0f);
        COMPLETE_OBJECT.setColorPaint(Color.PINK);
        
        List<Point2D.Double> pts = Arrays.asList(
            new Point2D.Double(1665.5, 987.0),
            new Point2D.Double(1601.5, 1037.0)
        );
        COMPLETE_OBJECT.setPts(pts);
        COMPLETE_OBJECT.setLabelWidth(112.53125);
        COMPLETE_OBJECT.setLabelHeight(13.125);
        COMPLETE_OBJECT.setLabels(new String[]{ " R=144 G=116 B=101"});
        COMPLETE_OBJECT.setLabelBounds(new Rectangle2D.Double(1539.234375, 1024.4375, 124.53125, 25.125));
    }
    
    @Override
    public String getTemplate() {
        return BASIC_TPL;
    }
    
    @Override
    public Object[] getParameters() {
        return new Object[]{ 
            PixelInfoGraphic.DEFAULT_FILLED, 
            PixelInfoGraphic.DEFAULT_LABEL_VISISIBLE,
            PixelInfoGraphic.DEFAULT_LINE_THICKNESS,
            getGraphicUuid(), 
            WProperties.color2Hexadecimal(PixelInfoGraphic.DEFAULT_COLOR, true) 
        };
    }

    @Override
    public void additionalTestsForDeserializeBasicGraphic(PixelInfoGraphic result, PixelInfoGraphic expected) {
        AnnotationGraphicTest.checkForDeserializeBasicGraphic(result, expected);
        
        assertThat(result.getPixelInfo()).isNull();
    }

    @Override
    public String getXmlFilePathCase0() {
        return XML_0;
    }

    @Override
    public String getXmlFilePathCase1() {
        return XML_1;
    }

    @Override
    public PixelInfoGraphic getExpectedDeserializeCompleteGraphic() {
        return COMPLETE_OBJECT;
    }
}
