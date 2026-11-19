package cc.monomer.control

import cc.monomer.pages.adapter.outbound.s3.pagecontent.PageContentObjectStorageConfiguration
import cc.monomer.pages.adapter.outbound.s3.pagecontent.S3PageContentObjectStorage
import cc.monomer.pages.domain.content.PageContentObjectStorage
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider

/** The active Control object-store composition is currently owned by Pages. */
@Configuration(proxyBeanMethods = false)
class ControlObjectStorageComposition {
    @Bean(destroyMethod = "close")
    fun pageContentObjectStorage(
        objectStorageProperties: ControlObjectStorageProperties,
        pageContentCredentials: AwsCredentialsProvider,
    ): PageContentObjectStorage =
        S3PageContentObjectStorage.create(
            configuration = PageContentObjectStorageConfiguration(
                endpoint = objectStorageProperties.endpoint,
                region = objectStorageProperties.region,
                bucket = objectStorageProperties.pageContent.bucket,
                rootDirectory = objectStorageProperties.pageContent.rootDirectory,
            ),
            credentialsProvider = pageContentCredentials,
        )

    @Bean
    fun pageContentCredentials(objectStorageProperties: ControlObjectStorageProperties): AwsCredentialsProvider =
        when (objectStorageProperties.credentialMode) {
            ControlArtifactCredentialMode.WORKLOAD -> DefaultCredentialsProvider.builder().build()
            ControlArtifactCredentialMode.LOCAL_TEST_STATIC -> StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                    requireNotNull(objectStorageProperties.accessKey),
                    requireNotNull(objectStorageProperties.secretKey),
                ),
            )
        }
}
