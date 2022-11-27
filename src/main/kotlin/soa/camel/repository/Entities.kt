package soa.camel.repository

import javax.persistence.*

@Entity
@Table(name = "tweetdata")
class TweetData (
    @Id
    val id: String?,

    @Column(name = "tweetbody")
    @Lob
    val tweetBody: String?
)
