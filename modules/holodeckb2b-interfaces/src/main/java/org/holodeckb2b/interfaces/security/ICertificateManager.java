/*
 * Copyright (C) 2017 The Holodeck B2B Team.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.holodeckb2b.interfaces.security;

import java.security.KeyStore;
import java.security.cert.Certificate;

/**
 * Defines the interface of the component of the <i>security provider</i> responsible for storing the keys used in the
 * security processing of messages.
 *
 * @author Sander Fieten (sander at holodeck-b2b.org)
 * @since HB2B_NEXT_VERSION
 */
public interface ICertificateManager {

    /**
     * Defines how a certificate can be used by the security module, i.e. whether it can be used for the verification of
     * a signature or for encryption of a message or both.
     */
    public enum CertificateUsage {
        /**
         * Indicates the certificate can be used for verification of the message signature
         */
        Signing,
        /**
         * Indicates the certificate can be used for encryption of the message
         */
        Encryption
    }

    /**
     * Registers a new key pair for signing and decrypting of messages under the given alias. The key pair must contain
     * the private key and at least one certificate containing the corresponding public key. It may contain the complete
     * certificate chain. This will be needed when this private key is used for signing and the certificate chain is to
     * be included in the <i>BSTReference</i>.
     * <p>The alias the key pair is registered under is used to identify the key pair in the P-Mode and must be unique
     * for all key pairs.
     *
     * @param key       The key pair to register
     * @param alias     The alias to register the key pair under
     * @throws SecurityProcessingException  When there is a problem in registration of the key pair. This can be caused
     *                                      by a duplicate alias or missing information in the provided key pair.
     */
    void registerPrivateKeyPair(final KeyStore.PrivateKeyEntry key, final String alias)
                                                                                   throws SecurityProcessingException;

    /**
     * Registers a new X509v3 certificate for signature verification and/or encryption under the given alias. The alias
     * the certificate is registered under is used to identify it in the P-Mode and must be unique for all registered
     * certificates with the same usage.
     *
     * @param cert      The certificate to register
     * @param use       The function(s) for which the certificate can be used
     * @param alias     The alias to register the certificate under
     * @throws SecurityProcessingException When there is a problem in registration of the key pair. This can be caused
     *                                     by a duplicate alias.
     */
    void registerCertificate(final Certificate cert, final CertificateUsage[] use, final String alias)
                                                                                   throws SecurityProcessingException;

    /**
     * Gets the key pair registered under the given alias.
     *
     * @param alias The alias of the key pair to retrieve
     * @return      The key pair if it was found, or<br><code>null</code> if no key pair is registered under the given
     *              alias
     * @throws SecurityProcessingException When there is a problem in retrieving the key pair.
     */
    KeyStore.PrivateKeyEntry getPrivateKeyPair(final String alias) throws SecurityProcessingException;

    /**
     * Gets the certificate registered under the given alias and for the specified usage.
     *
     * @param use   The function for which the certificate can be used
     * @param alias The alias of the certificate to retrieve
     * @return      The certificate if it was found, or<br><code>null</code> if no certificate is registered under the
     *              given alias
     * @throws SecurityProcessingException When there is a problem in retrieving the certificate.
     */
    Certificate getCertificate(final CertificateUsage use, final String alias) throws SecurityProcessingException;

    /**
     * Removes the key pair registered under the given alias.
     * <p>NOTE: Removing a key pair should only be done when there is no active P-Mode referencing it as Holodeck B2B
     * will not be able to process messages under this P-Mode. An implementation may check that the key pair being
     * removed is not in use before removing it.
     *
     * @param alias The alias of the key pair to remove
     * @throws SecurityProcessingException When there is a problem in retrieving the certificate. This can be caused by
     *                                     an unknown alias or the key pair still being used by a P-Mode.
     */
    void removePrivateKeyPair(final String alias) throws SecurityProcessingException;

    /**
     * Removes the certificate registered under the given alias and specified usage.
     * <p>NOTE: Removing a certificate should only be done when there is no active P-Mode referencing or depending on it
     * as Holodeck B2B will not be able to process messages under this P-Mode. An implementation may check that the
     * certificate being removed is not in use before removing it.
     *
     * @param alias The alias of the certificate to remove
     * @param use   The function(s) for which the certificate should not be used anymore
     * @throws SecurityProcessingException When there is a problem in retrieving the certificate. This can be caused by
     *                                     an unknown alias or the key pair still being used by a P-Mode.
     */
    void removeCertificate(final String alias, final CertificateUsage[] use) throws SecurityProcessingException;
}
