/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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

package io.github.dsheirer.gui.playlist.streaming;

import io.github.dsheirer.audio.broadcast.BroadcastServerType;
import io.github.dsheirer.audio.broadcast.zello.ZelloConsumerConfiguration;
import io.github.dsheirer.gui.control.IntegerTextField;
import io.github.dsheirer.playlist.PlaylistManager;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Zello Consumer (Friends & Family) channel streaming configuration editor.
 *
 * Fields:
 * - Name: Display name for this stream configuration
 * - Channel: Zello channel name to stream audio to
 * - Username: Zello account username
 * - Password: Zello account password
 * - Auth Token: JWT token (required for Zello Consumer)
 * - Max Recording Age: Maximum age of audio recording before it is discarded
 * - Stream Guard: Minimum gap between streams (ms)
 * - Pause Time: Delay between transmissions (ms)
 * - Relaxation Time: Hold-over before ending stream (ms)
 */
public class ZelloConsumerEditor extends AbstractBroadcastEditor<ZelloConsumerConfiguration>
{
    private static final Logger mLog = LoggerFactory.getLogger(ZelloConsumerEditor.class);

    private TextField mChannelTextField;
    private TextField mUsernameTextField;
    private PasswordField mPasswordField;
    private TextField mAuthTokenTextField;
    private IntegerTextField mMaxAgeTextField;
    private IntegerTextField mStreamGuardTextField;
    private IntegerTextField mPauseTimeTextField;
    private IntegerTextField mRelaxationTimeTextField;
    private GridPane mEditorPane;

    /**
     * Constructs an instance
     * @param playlistManager for accessing the broadcast model
     */
    public ZelloConsumerEditor(PlaylistManager playlistManager)
    {
        super(playlistManager);
    }

    @Override
    public void setItem(ZelloConsumerConfiguration item)
    {
        super.setItem(item);

        getChannelTextField().setDisable(item == null);
        getUsernameTextField().setDisable(item == null);
        getPasswordField().setDisable(item == null);
        getAuthTokenTextField().setDisable(item == null);
        getMaxAgeTextField().setDisable(item == null);
        getStreamGuardTextField().setDisable(item == null);
        getPauseTimeTextField().setDisable(item == null);
        getRelaxationTimeTextField().setDisable(item == null);

        if(item != null)
        {
            getChannelTextField().setText(item.getChannel());
            getUsernameTextField().setText(item.getUsername());
            getPasswordField().setText(item.getPassword());
            getAuthTokenTextField().setText(item.getAuthToken());
            getMaxAgeTextField().set((int)(item.getMaximumRecordingAge() / 1000));
            getStreamGuardTextField().set(item.getStreamGuardMs());
            getPauseTimeTextField().set(item.getPauseTimeMs());
            getRelaxationTimeTextField().set(item.getRelaxationTimeMs());
        }
        else
        {
            getChannelTextField().setText(null);
            getUsernameTextField().setText(null);
            getPasswordField().setText(null);
            getAuthTokenTextField().setText(null);
            getMaxAgeTextField().set(0);
            getStreamGuardTextField().set(0);
            getPauseTimeTextField().set(0);
            getRelaxationTimeTextField().set(700);
        }

        modifiedProperty().set(false);
    }

    @Override
    public void dispose()
    {
    }

    @Override
    public void save()
    {
        if(getItem() != null)
        {
            getItem().setChannel(getChannelTextField().getText());
            getItem().setUsername(getUsernameTextField().getText());
            getItem().setPassword(getPasswordField().getText());
            getItem().setAuthToken(getAuthTokenTextField().getText());
            getItem().setMaximumRecordingAge(getMaxAgeTextField().get() * 1000);
            getItem().setStreamGuardMs(getStreamGuardTextField().get());
            getItem().setPauseTimeMs(getPauseTimeTextField().get());
            getItem().setRelaxationTimeMs(getRelaxationTimeTextField().get());
        }

        super.save();
    }

    @Override
    public BroadcastServerType getBroadcastServerType()
    {
        return BroadcastServerType.ZELLO;
    }

    @Override
    protected GridPane getEditorPane()
    {
        if(mEditorPane == null)
        {
            mEditorPane = new GridPane();
            mEditorPane.setPadding(new Insets(10, 5, 10, 10));
            mEditorPane.setVgap(10);
            mEditorPane.setHgap(5);

            int row = 0;

            // Row 0: Format and Enabled
            Label formatLabel = new Label("Format");
            GridPane.setHalignment(formatLabel, HPos.RIGHT);
            GridPane.setConstraints(formatLabel, 0, row);
            mEditorPane.getChildren().add(formatLabel);

            GridPane.setConstraints(getFormatField(), 1, row);
            mEditorPane.getChildren().add(getFormatField());

            Label enabledLabel = new Label("Enabled");
            GridPane.setHalignment(enabledLabel, HPos.RIGHT);
            GridPane.setConstraints(enabledLabel, 2, row);
            mEditorPane.getChildren().add(enabledLabel);

            GridPane.setConstraints(getEnabledSwitch(), 3, row);
            mEditorPane.getChildren().add(getEnabledSwitch());

            // Row 1: Name
            Label nameLabel = new Label("Name");
            GridPane.setHalignment(nameLabel, HPos.RIGHT);
            GridPane.setConstraints(nameLabel, 0, ++row);
            mEditorPane.getChildren().add(nameLabel);

            GridPane.setConstraints(getNameTextField(), 1, row);
            mEditorPane.getChildren().add(getNameTextField());

            // Row 2: Channel
            Label channelLabel = new Label("Channel");
            GridPane.setHalignment(channelLabel, HPos.RIGHT);
            GridPane.setConstraints(channelLabel, 0, ++row);
            mEditorPane.getChildren().add(channelLabel);

            GridPane.setConstraints(getChannelTextField(), 1, row);
            mEditorPane.getChildren().add(getChannelTextField());

            // Row 3: Username
            Label usernameLabel = new Label("Username");
            GridPane.setHalignment(usernameLabel, HPos.RIGHT);
            GridPane.setConstraints(usernameLabel, 0, ++row);
            mEditorPane.getChildren().add(usernameLabel);

            GridPane.setConstraints(getUsernameTextField(), 1, row);
            mEditorPane.getChildren().add(getUsernameTextField());

            // Row 4: Password
            Label passwordLabel = new Label("Password");
            GridPane.setHalignment(passwordLabel, HPos.RIGHT);
            GridPane.setConstraints(passwordLabel, 0, ++row);
            mEditorPane.getChildren().add(passwordLabel);

            GridPane.setConstraints(getPasswordField(), 1, row);
            mEditorPane.getChildren().add(getPasswordField());

            // Row 5: Auth Token (required)
            Label tokenLabel = new Label("Auth Token (required)");
            GridPane.setHalignment(tokenLabel, HPos.RIGHT);
            GridPane.setConstraints(tokenLabel, 0, ++row);
            mEditorPane.getChildren().add(tokenLabel);

            GridPane.setConstraints(getAuthTokenTextField(), 1, row);
            mEditorPane.getChildren().add(getAuthTokenTextField());

            // Row 6: Max Recording Age
            Label maxAgeLabel = new Label("Max Recording Age (seconds)");
            GridPane.setHalignment(maxAgeLabel, HPos.RIGHT);
            GridPane.setConstraints(maxAgeLabel, 0, ++row);
            mEditorPane.getChildren().add(maxAgeLabel);

            GridPane.setConstraints(getMaxAgeTextField(), 1, row);
            mEditorPane.getChildren().add(getMaxAgeTextField());

            // Row 7: Stream Guard Timeout
            Label streamGuardLabel = new Label("Stream Guard (ms)");
            GridPane.setHalignment(streamGuardLabel, HPos.RIGHT);
            GridPane.setConstraints(streamGuardLabel, 0, ++row);
            mEditorPane.getChildren().add(streamGuardLabel);

            GridPane.setConstraints(getStreamGuardTextField(), 1, row);
            mEditorPane.getChildren().add(getStreamGuardTextField());

            Label streamGuardHint = new Label("Min gap between streams (0 = disabled)");
            GridPane.setConstraints(streamGuardHint, 2, row, 2, 1);
            mEditorPane.getChildren().add(streamGuardHint);

            // Row 8: Pause Time
            Label pauseTimeLabel = new Label("Pause Time (ms)");
            GridPane.setHalignment(pauseTimeLabel, HPos.RIGHT);
            GridPane.setConstraints(pauseTimeLabel, 0, ++row);
            mEditorPane.getChildren().add(pauseTimeLabel);

            GridPane.setConstraints(getPauseTimeTextField(), 1, row);
            mEditorPane.getChildren().add(getPauseTimeTextField());

            Label pauseTimeHint = new Label("Delay between transmissions (0 = off)");
            GridPane.setConstraints(pauseTimeHint, 2, row, 2, 1);
            mEditorPane.getChildren().add(pauseTimeHint);

            // Row 9: Relaxation Time
            Label relaxationLabel = new Label("Relaxation Time (ms)");
            GridPane.setHalignment(relaxationLabel, HPos.RIGHT);
            GridPane.setConstraints(relaxationLabel, 0, ++row);
            mEditorPane.getChildren().add(relaxationLabel);

            GridPane.setConstraints(getRelaxationTimeTextField(), 1, row);
            mEditorPane.getChildren().add(getRelaxationTimeTextField());

            Label relaxationHint = new Label("Hold-over before ending stream (0 = off)");
            GridPane.setConstraints(relaxationHint, 2, row, 2, 1);
            mEditorPane.getChildren().add(relaxationHint);
        }

        return mEditorPane;
    }

    private TextField getChannelTextField()
    {
        if(mChannelTextField == null)
        {
            mChannelTextField = new TextField();
            mChannelTextField.setDisable(true);
            mChannelTextField.setPrefWidth(300);
            mChannelTextField.setPromptText("Zello channel name");
            mChannelTextField.textProperty().addListener(mEditorModificationListener);
        }
        return mChannelTextField;
    }

    private TextField getUsernameTextField()
    {
        if(mUsernameTextField == null)
        {
            mUsernameTextField = new TextField();
            mUsernameTextField.setDisable(true);
            mUsernameTextField.textProperty().addListener(mEditorModificationListener);
        }
        return mUsernameTextField;
    }

    private PasswordField getPasswordField()
    {
        if(mPasswordField == null)
        {
            mPasswordField = new PasswordField();
            mPasswordField.setDisable(true);
            mPasswordField.textProperty().addListener(mEditorModificationListener);
        }
        return mPasswordField;
    }

    private TextField getAuthTokenTextField()
    {
        if(mAuthTokenTextField == null)
        {
            mAuthTokenTextField = new TextField();
            mAuthTokenTextField.setDisable(true);
            mAuthTokenTextField.setPrefWidth(300);
            mAuthTokenTextField.setPromptText("JWT authentication token");
            mAuthTokenTextField.textProperty().addListener(mEditorModificationListener);
        }
        return mAuthTokenTextField;
    }

    private IntegerTextField getMaxAgeTextField()
    {
        if(mMaxAgeTextField == null)
        {
            mMaxAgeTextField = new IntegerTextField();
            mMaxAgeTextField.setDisable(true);
            mMaxAgeTextField.textProperty().addListener(mEditorModificationListener);
        }
        return mMaxAgeTextField;
    }

    private IntegerTextField getStreamGuardTextField()
    {
        if(mStreamGuardTextField == null)
        {
            mStreamGuardTextField = new IntegerTextField();
            mStreamGuardTextField.setDisable(true);
            mStreamGuardTextField.textProperty().addListener(mEditorModificationListener);
        }
        return mStreamGuardTextField;
    }

    private IntegerTextField getPauseTimeTextField()
    {
        if(mPauseTimeTextField == null)
        {
            mPauseTimeTextField = new IntegerTextField();
            mPauseTimeTextField.setDisable(true);
            mPauseTimeTextField.textProperty().addListener(mEditorModificationListener);
        }
        return mPauseTimeTextField;
    }

    private IntegerTextField getRelaxationTimeTextField()
    {
        if(mRelaxationTimeTextField == null)
        {
            mRelaxationTimeTextField = new IntegerTextField();
            mRelaxationTimeTextField.setDisable(true);
            mRelaxationTimeTextField.textProperty().addListener(mEditorModificationListener);
        }
        return mRelaxationTimeTextField;
    }
}
