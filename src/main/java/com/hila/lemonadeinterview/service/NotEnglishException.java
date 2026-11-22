package com.hila.lemonadeinterview.service;

import java.io.IOException;

/** The content has more than 1% non-Latin letters. Only logged by the background job. */
public class NotEnglishException extends IOException {

    public NotEnglishException(long nonLatinLetters, long letters) {
        super("422 not English: " + nonLatinLetters + " of " + letters + " letters are not a-z/A-Z");
    }
}
