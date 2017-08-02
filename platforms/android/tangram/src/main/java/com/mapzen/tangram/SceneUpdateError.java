package com.mapzen.tangram;

import android.support.annotation.Keep;

import com.mapzen.tangram.MapController.Error;

/**
 * {@code SceneUpdateError} Holds an error status and its associated scene updated
 */
@Keep
public class SceneUpdateError {


    private SceneUpdate sceneUpdate;
    private Error error;

    private SceneUpdateError(String sceneUpdatePath, String sceneUpdateValue, int error) {
        this.sceneUpdate = new SceneUpdate(sceneUpdatePath, sceneUpdateValue);
        this.error = Error.values()[error];
    }

    public SceneUpdate getSceneUpdate() {
        return this.sceneUpdate;
    }

    public Error getError() {
        return this.error;
    }
}
