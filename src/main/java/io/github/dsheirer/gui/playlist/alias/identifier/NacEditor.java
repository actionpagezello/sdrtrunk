/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.gui.playlist.alias.identifier;

import io.github.dsheirer.alias.id.nac.Nac;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.GridPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Editor for P25 Network Access Code (NAC) alias identifiers
 */
public class NacEditor extends IdentifierEditor<Nac>
{
    private static final Logger mLog = LoggerFactory.getLogger(NacEditor.class);
    private Spinner<Integer> mNacSpinner;

    /**
     * Constructs an instance
     */
    public NacEditor()
    {
        GridPane gridPane = new GridPane();
        gridPane.setHgap(5);

        Label typeLabel = new Label("NAC (0-4095 / 0x000-0xFFF)");
        GridPane.setConstraints(typeLabel, 0, 0);
        gridPane.getChildren().add(typeLabel);

        GridPane.setConstraints(getNacSpinner(), 1, 0);
        gridPane.getChildren().add(getNacSpinner());

        getChildren().add(gridPane);
    }

    @Override
    public void setItem(Nac item)
    {
        super.setItem(item);
        if(item.isValid())
        {
            getNacSpinner().getValueFactory().setValue(item.getNacValue());
        }
        modifiedProperty().set(false);
    }

    @Override
    public void save()
    {
        //no-op
    }

    @Override
    public void dispose()
    {
        //no-op
    }

    /**
     * Spinner for NAC value selection (0-4095)
     */
    private Spinner<Integer> getNacSpinner()
    {
        if(mNacSpinner == null)
        {
            SpinnerValueFactory<Integer> valueFactory =
                new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 4095, 0);
            mNacSpinner = new Spinner<>(valueFactory);
            mNacSpinner.setEditable(true);
            mNacSpinner.setPrefWidth(100);
            mNacSpinner.valueProperty().addListener((observable, oldValue, newValue) -> {
                if(getItem() != null)
                {
                    getItem().setNacValue(newValue != null ? newValue : 0);
                    modifiedProperty().set(true);
                }
            });
        }

        return mNacSpinner;
    }
}
