import java.nio.file.Paths
import java.util.concurrent.Executors

import cats.effect.IO
import fs2.text
import fs2.io.file._

import scala.concurrent.ExecutionContext

/**
  * Created by denis on 8/12/16.
  */
object CirceMain {

  def main(args: Array[String]) {

    case class Person(firstName: String, lastName: String, age: Int)
    val person = Person("Joe", "Black", 42)

    // Manual
    {
      import io.circe.Encoder
      import io.circe.syntax._
      implicit val personEnc: Encoder[Person] = Encoder.forProduct3(
        "firstName", "lastName", "age"){ src =>  (src.firstName, src.lastName, src.age) }
      println(person.asJson)
    }

    // Semi-automatic
    {
      import io.circe.syntax._
      import io.circe.generic.semiauto._
      implicit val personEnc = deriveEncoder[Person]
      println(person.asJson)
    }

    // Automatic
    {
      import io.circe.syntax._
      import io.circe.generic.auto._
      println(person.asJson)
    }

    val jsonStr = """{ "firstName" : "Joe", "lastName" : "Black", "age" : 42 }"""

    // Manual decoder
    {
      {
        import io.circe.Decoder
        import io.circe.jawn._
        implicit val personDecoder: Decoder[Person] = Decoder.forProduct3(
          "firstName", "lastName", "age")(Person.apply)
        val person = decode[Person](jsonStr)
        println(person)
      }

      {
        import io.circe.Decoder
        import io.circe.jawn._
        implicit val personDecoder = for {
          firstName <- Decoder.instance(_.get[String]("firstName"))
          lastName <- Decoder.instance(_.get[String]("lastName"))
          age <- Decoder.instance(_.get[Int]("age"))
        } yield Person(firstName, lastName, age)
        val person = decode[Person](jsonStr)
        println(person)
      }

      {
        import io.circe.Decoder
        import io.circe.jawn._
        import cats.syntax.apply._

        val firstNameD = Decoder.instance(_.get[String]("firstName"))
        val lastNameD = Decoder.instance(_.get[String]("lastName"))
        val ageD = Decoder.instance(_.get[Int]("age"))
        implicit val personDecoder = (firstNameD, lastNameD, ageD).mapN(Person.apply)
        val person = decode[Person](jsonStr)
        println(person)
      }
    }

    // Semi-automatic decoder
    {
      import io.circe.generic.semiauto._
      import io.circe.jawn._
      implicit val personDec = deriveDecoder[Person]
      val person = decode[Person](jsonStr)
      println(person)
    }

    // Automatic
    {
      import io.circe.jawn._
      import io.circe.generic.auto._
      val person = decode[Person](jsonStr)
      println(person)
    }

    // Parsing numbers
    {
      val jsonStr =
        """{ "firstName" : "Joe", "lastName" : "Black", "age" : 42,
          |"address": { "street": "Market st.", "city": "Sydney",
          |"postal": 2000, "state": "NSW" },
          |"departments": ["dev", "hr", "qa"] }""".stripMargin

      import io.circe.jawn._
      import io.circe.syntax._
      val result = parse(jsonStr)
      // result: Either[Error, Json]

      import io.circe.Json
      val json = result.getOrElse(Json.Null)
      val cursor = json.hcursor

      cursor.downField("departments").downArray.right.
        withFocus(_.withString(_.toUpperCase.asJson))

      val modified = result.map { json =>
          json.hcursor.downField("age").
            withFocus(_.withNumber(_.toInt.map(n => n + 1).asJson))
        }

      modified.fold(
        fail => println(fail.message),
        res => println(res.top)
      )

      // Using optics
      {
        val jsonStr = """{
          "firstName":"Joe",
          "address":{
            "street":"Market st.",
            "city":"Sydney"
          }
        }"""

        import io.circe.jawn._
        val result = parse(jsonStr)
        val json = result.getOrElse(Json.Null)


        import io.circe.optics.JsonPath._
        val cityO = root.address.city.string
        // cityO: Optional[Json, String]
        println(cityO.getOption(json))
        // prints Some(Sydney)

        val secondDepO = root.departments.index(1).string
        println(secondDepO.getOption(json))
        // prints Some(hr)

        val updatedJson = cityO.set("Newcastle")(json)

        import io.circe.Printer
        val updatedStr = updatedJson.pretty(Printer.spaces2)
        println(updatedStr)
      }
    }
//    readingLargeFile()
  }

  def readingLargeFile(): Unit = {
    // Reading a large file
    case class Company(name: String, permalink: String, homepage_url: String)

    import io.circe.jawn._
    import io.circe.generic.auto._

    implicit val contextShift = IO.contextShift(ExecutionContext.Implicits.global)
    val blockingContext = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

    // download link: http://jsonstudio.com/wp-content/uploads/2014/02/companies.zip
    val filePath = Paths.get("companies.json")
    val byteStr = readAll[IO](filePath, blockingContext, 1024)
    val lineStr = byteStr.through(text.utf8Decode).through(text.lines)
    val resultT = lineStr.map { line =>
      decode[Company](line)
    }.filter(_.isRight).map { company =>
      company.foreach(println)
      company
    }.take(5).compile.toVector

    val diff = System.currentTimeMillis()
    resultT.unsafeRunSync()
    println(s"Elapsed: ${System.currentTimeMillis() - diff}")
  }
}
